package spoon.kotlin.compiler.ir

import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import spoon.SpoonException
import spoon.kotlin.ktMetadata.KtMetadataKeys
import spoon.kotlin.reflect.KtStatementExpression
import spoon.kotlin.reflect.KtStatementExpressionImpl
import spoon.reflect.code.*
import spoon.reflect.declaration.*
import spoon.reflect.factory.Factory
import spoon.reflect.reference.CtTypeReference

class IrTreeBuilder(val factory: Factory): IrElementVisitor<CompositeTransformResult<CtElement>, ContextData> {
    val referenceBuilder = IrReferenceBuilder(this)
    val helper = IrTreeBuilderHelper(this)
    private val core get() = factory.Core()

    override fun visitElement(element: IrElement, data: ContextData): CompositeTransformResult<CtElement> {
        //TODO("Not yet implemented")
        return factory.Core().createContinue().compose()
    }

    override fun visitClass(declaration: IrClass, data: ContextData): CompositeTransformResult<CtElement> {
        val module = helper.getOrCreateModule()
        val type = helper.createType(declaration)
        val isObject = type.getMetadata(KtMetadataKeys.CLASS_IS_OBJECT) as Boolean? == true
        val containingDecl = declaration.descriptor.containingDeclaration
        if(containingDecl is PackageFragmentDescriptor) {
            val pkg = if (containingDecl.fqName.isRoot) module.rootPackage else
                factory.Package().getOrCreate(containingDecl.fqName.asString(), module)
            pkg.addType<CtPackage>(type)
        }

        // Modifiers
        val modifierList = IrToModifierKind.fromClass(declaration)
        type.addModifiersAsMetadata(modifierList)

        // Type params
        if(declaration.typeParameters.isNotEmpty()) {
            type.setFormalCtTypeParameters<CtType<*>>(
                declaration.typeParameters.map { visitTypeParameter(it, data).result() })
        }

        for(decl in declaration.declarations) {
            val ctDecl = decl.accept(this, data).single
            ctDecl.setParent(type)
            when(ctDecl) {
                is CtEnumValue<*> -> {
                    (type as CtEnum<Enum<*>>).addEnumValue<CtEnum<Enum<*>>>(ctDecl)
                }
                is CtField<*> -> type.addField(ctDecl)
                is CtMethod<*> -> {
                    if (declaration.isInterface && ctDecl.body != null) {
                        ctDecl.setDefaultMethod<Nothing>(true)
                    }
                    //if(decl.psi is KtClass) {
                     //   ctDecl.setImplicit<CtMethod<*>>(true)
                   // }
                    type.addMethod(ctDecl)
                }
                is CtConstructor<*> -> {
                    if (type is CtClass<*> && !isObject) {
                        (type as CtClass<Any>).addConstructor<CtClass<Any>>(ctDecl as CtConstructor<Any>)
                    }
                }
                is CtTypeMember -> {
                    type.addTypeMember(ctDecl)
                }
            }
        }

        return type.compose()
    }

    override fun visitTypeParameter(declaration: IrTypeParameter, data: ContextData):
            CompositeTransformResult.Single<CtTypeParameter> {
        val ctTypeParam = factory.Core().createTypeParameter()
        ctTypeParam.setSimpleName<CtTypeParameter>(declaration.name.identifier)
        val bounds = declaration.superTypes.map { referenceBuilder.getNewTypeReference<Any>(it) }
        if(bounds.size == 1) {
            ctTypeParam.setSuperclass<CtTypeParameter>(bounds[0])
        } else if(bounds.size > 1) {
            ctTypeParam.setSuperclass<CtTypeParameter>(
                factory.Type().createIntersectionTypeReferenceWithBounds<Any>(bounds)
            )
        }

        ctTypeParam.addModifiersAsMetadata(IrToModifierKind.fromTypeVariable(declaration))
        return ctTypeParam.compose()
    }

    override fun <T> visitConst(expression: IrConst<T>, data: ContextData): CompositeTransformResult<CtElement> {
        val value = when(expression.kind) {
            IrConstKind.Null -> null
            IrConstKind.Boolean -> expression.value as Boolean
            IrConstKind.Char -> expression.value as Char
            IrConstKind.Byte -> expression.value as Byte
            IrConstKind.Short -> expression.value as Short
            IrConstKind.Int -> expression.value as Int
            IrConstKind.Long -> expression.value as Long
            IrConstKind.String -> expression.value as String
            IrConstKind.Float -> expression.value as Float
            IrConstKind.Double -> expression.value as Double
        }
        val ctLiteral: CtLiteral<T> = factory.Core().createLiteral()
        ctLiteral.setValue<CtLiteral<T>>(value as T)
        if(value == null)
            ctLiteral.setType<CtLiteral<T>>(factory.Type().nullType() as CtTypeReference<T>)
        else
            ctLiteral.setType<CtLiteral<T>>(referenceBuilder.getNewTypeReference(expression.type))
        return ctLiteral.compose()
    }

    override fun visitProperty(declaration: IrProperty, data: ContextData): CompositeTransformResult<CtElement> {
        val ctField = core.createField<Any>()

        // Initializer (if any) exists in backing field initializer
        val backingField = declaration.backingField
        val initializer = backingField?.initializer
        if(initializer != null) {
            val ctInitializer = visitExpressionBody(initializer, data).result()

            if(backingField.origin == IrDeclarationOrigin.DELEGATE) {
                ctField.putMetadata<CtElement>(KtMetadataKeys.PROPERTY_DELEGATE, ctInitializer)
                ctInitializer.setParent(ctField)
            } else {
                ctField.setDefaultExpression<CtField<Any>>(
                    expressionOrWrappedInStatementExpression(ctInitializer))
            }
        }

        // Modifiers
        ctField.addModifiersAsMetadata(IrToModifierKind.fromProperty(declaration))

        // Type
        val type = if(backingField != null) {
            referenceBuilder.getNewTypeReference<Any>(backingField.type)
        } else if(declaration.getter != null) {
            referenceBuilder.getNewTypeReference<Any>(declaration.getter!!.returnType)
        } else {
            throw SpoonException("Unable to get IR type of property $declaration")
        }
        ctField.setType<CtField<*>>(type)

        // Mark implicit/explicit type
        // TODO

        // Check if property stems from constructor value parameter, then the property is implicit
        // TODO

        val getter = declaration.getter
        if(getter != null && getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
            ctField.putMetadata<CtField<*>>(KtMetadataKeys.PROPERTY_GETTER, visitSimpleFunction(getter, data))
        }

        val setter = declaration.setter
        if(setter != null && setter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
            ctField.putMetadata<CtField<*>>(KtMetadataKeys.PROPERTY_SETTER, visitSimpleFunction(setter, data))
        }

        return ctField.compose()
    }

    override fun visitExpressionBody(body: IrExpressionBody, data: ContextData):
            CompositeTransformResult.Single<CtElement> {

        return TODO()
    }

    private fun <T: CtElement> T.compose() = CompositeTransformResult.Single(this)
    private fun <T: CtElement> CompositeTransformResult.Single<T>.result() = _single
    private fun <T> CtExpression<T>.wrapInImplicitReturn() : CtReturn<T> {
        val r = factory.Core().createReturn<T>()
        r.setReturnedExpression<CtReturn<T>>(this)
        r.setImplicit<CtReturn<T>>(true)
        return r
    }
    private fun <T> CtStatement.wrapInStatementExpression(type : CtTypeReference<T>) : KtStatementExpression<T> {
        val se = KtStatementExpressionImpl<T>(this)
        se.setType<KtStatementExpression<T>>(type)
        this.setParent(se)
        return se
    }

    private fun statementOrWrappedInImplicitReturn(e: CtElement): CtStatement = when(e) {
        is CtStatement -> e
        is CtExpression<*> -> e.wrapInImplicitReturn()
        else -> throw RuntimeException("Can't wrap ${e::class} in StatementExpression")
    }

    @Suppress("UNCHECKED_CAST")
    private fun expressionOrWrappedInStatementExpression(e: CtElement): CtExpression<Any> {
        val statementExpression: KtStatementExpression<*>
        when (e) {
            is CtExpression<*> -> return e as CtExpression<Any>
            is CtIf -> {
                val typeRef = e.getMetadata(KtMetadataKeys.KT_STATEMENT_TYPE) as CtTypeReference<Any>
                statementExpression = e.wrapInStatementExpression(typeRef)
                statementExpression.setImplicit(true)
            }
            is CtBlock<*> -> {
                if (e.statements.size == 1) {
                    val statement = e.statements[0]
                    if (e.isImplicit && statement is CtExpression<*>) {
                        return statement as CtExpression<Any>
                    } else {
                        e.setImplicit<CtBlock<*>>(true)
                    }
                }
                val typeRef = e.getMetadata(KtMetadataKeys.KT_STATEMENT_TYPE) as CtTypeReference<Any>
                statementExpression = e.wrapInStatementExpression(typeRef)
                statementExpression.setImplicit(true)
            }
            else -> throw RuntimeException("Can't wrap ${e::class.simpleName} in StatementExpression")
        }
        return statementExpression
    }
}
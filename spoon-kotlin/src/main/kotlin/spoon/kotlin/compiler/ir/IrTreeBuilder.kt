package spoon.kotlin.compiler.ir

import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrTemporaryVariableDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrIfThenElseImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.psi2ir.generators.AUGMENTED_ASSIGNMENTS
import org.jetbrains.kotlin.psi2ir.generators.INCREMENT_DECREMENT_OPERATORS
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import spoon.SpoonException
import spoon.kotlin.ktMetadata.KtMetadataKeys
import spoon.kotlin.reflect.KtModifierKind
import spoon.kotlin.reflect.KtStatementExpression
import spoon.kotlin.reflect.KtStatementExpressionImpl
import spoon.reflect.code.*
import spoon.reflect.declaration.*
import spoon.reflect.factory.Factory
import spoon.reflect.reference.*
import spoon.support.reflect.code.CtLiteralImpl

internal class IrTreeBuilder(
    val factory: Factory,
    val sourceManager: PsiSourceManager,
    private val detectImplicitTypes: Boolean = true,
    private val detectInfix: Boolean = true
) : IrElementVisitor<TransformResult<CtElement>, ContextData> {
    val referenceBuilder = IrReferenceBuilder(this)
    val helper = IrTreeBuilderHelper(this)
    private lateinit var sourceHelper: PsiSourceHelper

    fun getSourceHelper(contextData: ContextData): PsiSourceHelper {
        if(!this::sourceHelper.isInitialized)
            sourceHelper = PsiSourceHelper(sourceManager, contextData.file)
        return sourceHelper
    }

    val core get() = factory.Core()
    private fun Name.escaped() = helper.escapedIdentifier(this)
    internal val toplvlClassName = "<top-level>"

    private fun checkLabelOfStatement(irElement: IrElement, ctElement: CtElement, data: ContextData) {
        val label = getSourceHelper(data).getLabelOrNull(irElement)
        if(label != null) {
            val isSet: Boolean = if(ctElement is CtStatement) {
                ctElement.setLabel<CtStatement>(label)
                ctElement.label != null // Some statements have unsettable labels
            }
            else false
            if(!isSet){
                ctElement.putKtMetadata(
                    KtMetadataKeys.LABEL,
                    KtMetadata.wrap(label)
                )
            }
        }
    }

    override fun visitElement(element: IrElement, data: ContextData): TransformResult<CtElement> {
        //TODO("Not yet implemented")
        return CtLiteralImpl<String>().setValue<CtLiteral<String>>("Unimplemented element $element").definitely()
    }

    override fun visitFile(declaration: IrFile, data: ContextData): DefiniteTransformResult<CtCompilationUnit> {
        val module = helper.getOrCreateModule()
        val compilationUnit = factory.CompilationUnit().getOrCreate(declaration.name)

        val pkg = if(declaration.packageFragmentDescriptor.fqName.isRoot) module.rootPackage else
            factory.Package().getOrCreate(declaration.packageFragmentDescriptor.fqName.asString(), module)

        compilationUnit.declaredPackage = pkg
        compilationUnit.lineSeparatorPositions = declaration.fileEntry.lineStartOffsets

        for(subDeclaration in declaration.declarations) {
            val ctDecl = subDeclaration.accept(this, Empty(declaration)).resultUnsafe
            when(ctDecl) {
                is CtType<*> -> {
                    pkg.addType<CtPackage>(ctDecl)
                    compilationUnit.addDeclaredType(ctDecl)
                }
                is CtTypeMember -> {
                    val topLvl = pkg.getType<CtType<Any>>(toplvlClassName) ?:
                    (core.createClass<Any>().also {
                        topLvlClass ->
                        topLvlClass.setImplicit<CtClass<*>>(true)
                        topLvlClass.setSimpleName<CtClass<*>>(toplvlClassName)
                        pkg.addType<CtPackage>(topLvlClass)
                        ctDecl.putKtMetadata<CtTypeMember>(KtMetadataKeys.TOP_LEVEL_DECLARING_CU, KtMetadata.wrap(compilationUnit))
                    })
                    topLvl.addTypeMember<CtClass<Any>>(ctDecl)
                }
            }
        }

        return compilationUnit.definitely()
    }

    override fun visitClass(declaration: IrClass, data: ContextData): DefiniteTransformResult<CtElement> {
        val module = helper.getOrCreateModule()
        val type = helper.createType(declaration, data)
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
            type.setFormalCtTypeParameters<CtType<Any>>(
                declaration.typeParameters.map { visitTypeParameter(it, data).resultSafe })
        }

        for(decl in declaration.declarations) {
            if(decl.isFakeOverride) continue
            val ctDecl = decl.accept(this, data).resultUnsafe
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

        return type.definitely()
    }

    override fun visitAnonymousInitializer(
        declaration: IrAnonymousInitializer,
        data: ContextData
    ): DefiniteTransformResult<CtElement> {
        val ctAnonExecutable = core.createAnonymousExecutable()
        val body = visitBody(declaration.body, data).resultSafe
        ctAnonExecutable.setBody<CtAnonymousExecutable>(body)
        return ctAnonExecutable.definitely()
    }

    override fun visitTypeParameter(declaration: IrTypeParameter, data: ContextData):
            DefiniteTransformResult<CtTypeParameter> {
        val ctTypeParam = factory.Core().createTypeParameter()
        ctTypeParam.setSimpleName<CtTypeParameter>(declaration.name.escaped())
        // Don't include default upper bound ("Any?")
        val bounds = declaration.superTypes.filterNot { it.isNullableAny() }.map { referenceBuilder.getNewTypeReference<Any>(it) }
        if(bounds.size == 1) {
            ctTypeParam.setSuperclass<CtTypeParameter>(bounds[0])
        } else if(bounds.size > 1) {
            ctTypeParam.setSuperclass<CtTypeParameter>(
                factory.Type().createIntersectionTypeReferenceWithBounds<Any>(bounds)
            )
        }

        ctTypeParam.addModifiersAsMetadata(IrToModifierKind.fromTypeVariable(declaration))
        return ctTypeParam.definitely()
    }

    override fun <T> visitConst(expression: IrConst<T>, data: ContextData): DefiniteTransformResult<CtLiteral<*>> {
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
        else {
            ctLiteral.setType<CtLiteral<T>>(referenceBuilder.getNewTypeReference(expression.type))
            if(value is Number) {
                ctLiteral.setBase<CtLiteral<T>>(helper.getBaseOfConst(expression as IrConst<Number>, data.file))
            }
        }
        return ctLiteral.definitely()
    }

    override fun visitProperty(declaration: IrProperty, data: ContextData): DefiniteTransformResult<CtElement> {
        val ctField = core.createField<Any>()
        ctField.setSimpleName<CtField<*>>(declaration.name.escaped())

        // Initializer (if any) exists in backing field initializer
        val backingField = declaration.backingField
        val initializer = backingField?.initializer
        if(initializer != null) {
            val ctInitializer = visitExpressionBody(initializer, data).resultUnsafe

            if(backingField.origin == IrDeclarationOrigin.DELEGATE) {
                ctField.putKtMetadata<CtElement>(KtMetadataKeys.PROPERTY_DELEGATE, KtMetadata.wrap(ctInitializer))
                ctInitializer.setParent(ctField)
            } else {
                ctField.setDefaultExpression<CtField<Any>>(
                    expressionOrWrappedInStatementExpression(ctInitializer))
            }

            // Check if property stems from primary constructor parameter
            val getVal = initializer.expression
            if(getVal is IrValueAccessExpression &&
                getVal.origin == IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER) {
                ctField.setImplicit<CtField<*>>(true)
            }
        }

        // Modifiers
        ctField.addModifiersAsMetadata(IrToModifierKind.fromProperty(declaration))

        // Type
        val type = referenceBuilder.getNewTypeReference<Any>(declaration.descriptor.type)

        // Mark implicit/explicit type
        val implicitType = detectImplicitTypes &&
                !getSourceHelper(data).hasExplicitType(declaration.descriptor.source.getPsi() as? KtProperty?)
        type.setImplicit<CtTypeReference<*>>(implicitType)
        ctField.setType<CtField<*>>(type)

        val getter = declaration.getter
        if(getter != null && getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
            ctField.putKtMetadata<CtField<*>>(KtMetadataKeys.PROPERTY_GETTER,
                KtMetadata.wrap(createUnnamedFunction(getter, data)))
        }

        val setter = declaration.setter
        if(setter != null && setter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
            ctField.putKtMetadata<CtField<*>>(KtMetadataKeys.PROPERTY_SETTER,
                KtMetadata.wrap(createUnnamedFunction(setter, data)))
        }

        return ctField.definitely()
    }

    override fun visitDelegatingConstructorCall(
        expression: IrDelegatingConstructorCall,
        data: ContextData
    ): DefiniteTransformResult<CtElement> {
        val ctConstructorCall = core.createConstructorCall<Any>()
        ctConstructorCall.setExecutable<CtConstructorCall<Any>>(referenceBuilder.getNewExecutableReference(expression))

        val valueArgs = ArrayList<CtExpression<*>>(expression.valueArgumentsCount)
        for(i in 0 until expression.valueArgumentsCount) {
            val ctExpr = expression.getValueArgument(i)!!.accept(this, data).resultUnsafe
            valueArgs.add(expressionOrWrappedInStatementExpression(ctExpr))
        }
        if(valueArgs.isNotEmpty()) {
            ctConstructorCall.setArguments<CtConstructorCall<Any>>(valueArgs)
        }
        return ctConstructorCall.definitely()
    }

    override fun visitConstructor(declaration: IrConstructor, data: ContextData): DefiniteTransformResult<CtElement> {
        val ctConstructor = core.createConstructor<Any>()
        ctConstructor.setSimpleName<CtConstructor<*>>(declaration.name.asString())

        val modifierList = listOfNotNull(KtModifierKind.convertVisibility(declaration.visibility))
        ctConstructor.setImplicit<CtConstructor<Any>>(declaration.isPrimary &&
                declaration.valueParameters.isEmpty() &&
                declaration.descriptor.source.getPsi() !is KtPrimaryConstructor &&
                modifierList.filterNot { it == KtModifierKind.PUBLIC }.isEmpty()
        )
        ctConstructor.putKtMetadata(
            KtMetadataKeys.KT_MODIFIERS,
            KtMetadata.wrap(modifierList))
        ctConstructor.putKtMetadata(
            KtMetadataKeys.CONSTRUCTOR_IS_PRIMARY,
            KtMetadata.wrap(declaration.isPrimary))

        // Add body
        val body = declaration.body?.accept(this, data)?.resultOrNull as CtStatement?
        if(body != null) {
            ctConstructor.setBody<CtConstructor<*>>(body)
        }

        for(valueParam in declaration.valueParameters) {
            val ctParam = visitValueParameter(valueParam, data).resultSafe

            /*
            * Primary constructor property declaration creates implicit properties in the class. An implicit property is the
            * holder of the val/var modifier, not the parameter:
            * ClassName(var x = 2) >translates to> ClassName(x = 2) { var x = x }
            * To facilitate printing, we look in the PSI if the parameter has modifiers and add them to metadata.
            *
            * TODO: Perhaps add metadata mapping property <-> param?
            *  */
            if(declaration.isPrimary) {
                val pModifiers = (ctParam.getMetadata(KtMetadataKeys.KT_MODIFIERS) as MutableSet<KtModifierKind>?) ?:
                mutableSetOf<KtModifierKind>()

                val psiModifierList = valueParam.descriptor.source.getPsi()?.getChildOfType<KtModifierList>()?.let {
                        list -> KtModifierKind.fromPsiModifierList(list)
                    } ?: emptyList()

                pModifiers.addAll(psiModifierList)
                // Var/val might be outside of modifier list
                val psiTokens = valueParam.descriptor.source.getPsi()?.getChildrenOfType<LeafPsiElement>()
                if(psiTokens != null) {
                    for(token in psiTokens)
                        if(token.elementType == KtTokens.VAL_KEYWORD) {
                            pModifiers.add(KtModifierKind.VAL)
                        } else if(token.elementType == KtTokens.VAR_KEYWORD) {
                            pModifiers.add(KtModifierKind.VAR)
                        }
                }
                ctParam.putMetadata<CtParameter<*>>(KtMetadataKeys.KT_MODIFIERS, pModifiers)
            }
            ctConstructor.addParameter<CtConstructor<Any>>(ctParam)
        }
        return ctConstructor.definitely()
    }

    override fun visitInstanceInitializerCall(
        expression: IrInstanceInitializerCall,
        data: ContextData
    ): EmptyTransformResult<CtElement> {
        return EmptyTransformResult()
    }

    override fun visitLocalDelegatedProperty(
        declaration: IrLocalDelegatedProperty,
        data: ContextData
    ): TransformResult<CtElement> {
        return super.visitLocalDelegatedProperty(declaration, data)
    }

    override fun visitVariable(declaration: IrVariable, data: ContextData): DefiniteTransformResult<CtLocalVariable<*>> {
        val ctLocalVar = core.createLocalVariable<Any>()
        ctLocalVar.setSimpleName<CtVariable<*>>(declaration.name.escaped())

        // Initializer
        if(declaration.origin != IrDeclarationOrigin.FOR_LOOP_VARIABLE &&
                data !is Destruct) {
            val initializer = declaration.initializer?.accept(this, data)?.resultUnsafe
            if (initializer != null) {
                val initializerExpr = expressionOrWrappedInStatementExpression(initializer)
                ctLocalVar.setDefaultExpression<CtLocalVariable<Any>>(initializerExpr)
            }

            // Modifiers
            ctLocalVar.addModifiersAsMetadata(IrToModifierKind.fromVariable(declaration))
        }

        // Type
        val type = referenceBuilder.getNewTypeReference<Any>(declaration.type)
        ctLocalVar.setType<CtLocalVariable<*>>(type)

        // Mark implicit/explicit type
        val implicitType = detectImplicitTypes &&
                !getSourceHelper(data).hasExplicitType(declaration.descriptor.source.getPsi() as? KtProperty?)
        type.setImplicit<CtTypeReference<*>>(implicitType)
        ctLocalVar.setType<CtVariable<*>>(type)
        ctLocalVar.setInferred<CtLocalVariable<Any>>(implicitType)

        return ctLocalVar.definitely()
    }

    override fun visitValueParameter(
        declaration: IrValueParameter,
        data: ContextData
    ): DefiniteTransformResult<CtParameter<*>> {
        val ctParam = core.createParameter<Any>()
        ctParam.setSimpleName<CtParameter<Any>>(declaration.name.escaped())
        ctParam.setInferred<CtParameter<Any>>(false) // Not allowed

        // Modifiers
        val modifierList = IrToModifierKind.fromValueParameter(declaration)
        ctParam.addModifiersAsMetadata(modifierList)
        ctParam.setVarArgs<CtParameter<Any>>(KtModifierKind.VARARG in modifierList)

        val defaultValue = declaration.defaultValue?.let { visitExpressionBody(it, data) }?.resultUnsafe
        if(defaultValue != null) {
            ctParam.setDefaultExpression<CtParameter<Any>>(
                expressionOrWrappedInStatementExpression(defaultValue)
            )
        }

        // Type
        val type = if(declaration.isVararg) {
            declaration.varargElementType!!
        } else {
            declaration.type
        }
        ctParam.setType<CtParameter<Any>>(referenceBuilder.getNewTypeReference<Any>(type))

        // Mark implicit for "it" in lambda
        //TODO

        return ctParam.definitely()
    }

    private fun createUnnamedFunction(irFunction: IrFunction, data: ContextData): CtMethod<*> {
        val ctMethod = core.createMethod<Any>()

        // Value params
        if(irFunction.valueParameters.isNotEmpty()) {
            ctMethod.setParameters<CtMethod<Any>>(
                irFunction.valueParameters.map { visitValueParameter(it, data).resultSafe }
            )
        }

        // Type params
        if(irFunction.typeParameters.isNotEmpty()) {
            ctMethod.setFormalCtTypeParameters<CtMethod<Any>>(
                irFunction.typeParameters.map { visitTypeParameter(it, data).resultSafe }
            )
        }

        // Return type
        val type = referenceBuilder.getNewTypeReference<Any>(irFunction.returnType)
        if(detectImplicitTypes && !getSourceHelper(data).hasExplicitType(
                irFunction.descriptor.source.getPsi() as? KtFunction?)) {
            type.setImplicit<CtTypeReference<*>>(true)
        }
        ctMethod.setType<CtMethod<*>>(type)

        // Extension receiver
        val extensionReceiverRef = irFunction.extensionReceiverParameter?.type?.let {
            referenceBuilder.getNewTypeReference<Any>(it)
        }
        if(extensionReceiverRef != null) {
            extensionReceiverRef.setParent<CtReference>(extensionReceiverRef)
            ctMethod.putKtMetadata(KtMetadataKeys.EXTENSION_TYPE_REF,
                KtMetadata.wrap(extensionReceiverRef)
            )
        }

        // Body
        val body = irFunction.body
        if(body != null) {
            val ctBody = visitBody(body, data).resultSafe
            if(ctBody.statements.size == 1 && body.endOffset - body.startOffset == 1) {
                ctBody.setImplicit<CtBlock<*>>(true)
            }
            ctMethod.setBody<CtMethod<Any>>(ctBody)
        }
        return ctMethod
    }

    override fun visitSimpleFunction(
        declaration: IrSimpleFunction,
        data: ContextData
    ): DefiniteTransformResult<CtMethod<*>> {
        return createUnnamedFunction(declaration, data).also {
            it.setSimpleName<CtMethod<*>>(declaration.name.escaped())
            it.addModifiersAsMetadata(IrToModifierKind.fromFunctionDeclaration(declaration))
        }.definitely()
    }

    override fun visitBody(body: IrBody, data: ContextData): DefiniteTransformResult<CtBlock<*>> {
        val ctBlock = core.createBlock<Any>()
        val statements = ArrayList<CtStatement>()
        for(irStatement in body.statements) {
            if(irStatement is IrDeclaration && irStatement.isFakeOverride) continue
            val result = irStatement.accept(this, data)
            if(result.isNothing) continue
            val ctResult = result.resultUnsafe
            val ctStatement = if(ctResult is CtMethod<*>) ctResult.wrapLocalMethod() else
                statementOrWrappedInImplicitReturn(ctResult)
            checkLabelOfStatement(irStatement, ctStatement, data)
            statements.add(ctStatement)
        }
        ctBlock.setStatements<CtBlock<*>>(statements)
        return ctBlock.definitely()
    }

    private fun createAssignment(lhs: CtExpression<Any>, rhs: CtExpression<Any>): CtAssignment<Any, Any> {
        val ctAssignment = core.createAssignment<Any,Any>()
        ctAssignment.setAssigned<CtAssignment<Any, Any>>(lhs)
        ctAssignment.setAssignment<CtAssignment<Any, Any>>(rhs)
        return ctAssignment
    }

    private fun createAssignment(irCall: IrCall, data: ContextData): TransformResult<CtElement> {
        val callDescriptor = irCall.symbol.descriptor as PropertySetterDescriptor
        val receiver = getReceiver(irCall, data) as CtExpression<*>?
        val lhs = createVariableWrite(receiver, referenceBuilder.getNewVariableReference<Any>(
            callDescriptor.correspondingProperty))
        val rhs = irCall.getValueArgument(0)!!.accept(this, data).resultUnsafe
        return createAssignment(lhs, expressionOrWrappedInStatementExpression(rhs)).also {
            it.setType<CtExpression<*>>(referenceBuilder.getNewTypeReference(irCall.type))
        }.definitely()
    }

    private fun specialInvocation(irCall: IrCall, data: ContextData): TransformResult<CtElement> {
        val callDescriptor = irCall.symbol.descriptor
        if(callDescriptor is PropertyGetterDescriptor) {
            return visitPropertyAccess(irCall, data)
        }
        when(irCall.origin) {
            IrStatementOrigin.GET_PROPERTY -> return visitPropertyAccess(irCall, data)
            IrStatementOrigin.FOR_LOOP_ITERATOR -> {
                return createInvocation(irCall, data).resultSafe.target.definitely()
            }
            in AUGMENTED_ASSIGNMENTS -> return createAugmentedAssignmentOperator(irCall, irCall.origin!!, data).definitely()
            IrStatementOrigin.EQ -> {
                return if(callDescriptor is PropertySetterDescriptor) {
                    createAssignment(irCall, data)
                } else {
                    createInvocation(irCall, data).also { it.resultSafe.putKtMetadata(
                        KtMetadataKeys.SET_AS_OPERATOR,
                        KtMetadata.wrap(true)
                    ) }
                }
            }
            in INCREMENT_DECREMENT_OPERATORS -> {
                val receiver = getReceiver(irCall, data) as CtExpression<*>?
                val variable = referenceBuilder.getNewVariableReference<Any>(
                    (callDescriptor as PropertySetterDescriptor).correspondingProperty)
                return createVariableWrite(receiver, variable).definitely()
            }
        }
        if(OperatorHelper.isUnaryOperator(irCall.origin)) {
            return visitUnaryOperator(irCall, data)
        }
        if(OperatorHelper.isBinaryOperator(irCall.origin)) {
            return visitBinaryOperator(irCall, data)
        }
        return TransformResult.nothing()
    }

    override fun visitWhen(expression: IrWhen, data: ContextData): TransformResult<CtElement> {
        when(expression) {
            is IrIfThenElseImpl -> return visitIfThenElse(expression, data)
        }
        return super.visitWhen(expression, data)
    }

    fun visitIfThenElse(ifThenElse: IrIfThenElseImpl, data: ContextData): DefiniteTransformResult<CtIf> {
        val ctIf = core.createIf()
        val thenBranch = ifThenElse.branches.first { it !is IrElseBranch }
        val elseBranch = ifThenElse.branches.firstIsInstanceOrNull<IrElseBranch>()
        val condition = thenBranch.condition.accept(this, data).resultUnsafe
        val thenResult = thenBranch.result.accept(this, data).resultUnsafe

        ctIf.setCondition<CtIf>(condition as CtExpression<Boolean>)
        ctIf.setThenStatement<CtIf>(statementOrWrappedInImplicitReturn(thenResult))
        if(elseBranch != null) {
            val elseResult = elseBranch.result.accept(this,data).resultUnsafe
            ctIf.setElseStatement<CtIf>(statementOrWrappedInImplicitReturn(elseResult))
        }

        val type = referenceBuilder.getNewTypeReference<Any>(ifThenElse.type)
        ctIf.putKtMetadata(KtMetadataKeys.KT_STATEMENT_TYPE, KtMetadata.wrap(type))

        return ctIf.definitely()
    }

    private fun createInvocation(irCall: IrCall, data: ContextData): DefiniteTransformResult<CtInvocation<*>> {
        val invocation = core.createInvocation<Any>()
        invocation.setExecutable<CtInvocation<Any>>(referenceBuilder.getNewExecutableReference(irCall))

        val target = getReceiver(irCall, data)
        if(target is CtExpression<*>) {
            invocation.setTarget<CtInvocation<Any>>(target)
        } else if(target != null) {
            throw RuntimeException("Function call target not CtExpression")
        }

        if(irCall.valueArgumentsCount > 0) {
            invocation.setArguments<CtInvocation<Any>>(irCall.symbol.descriptor.valueParameters.map {
                expressionOrWrappedInStatementExpression(
                    irCall.getValueArgument(it.index)!!.accept(this, data).resultUnsafe
                )
            })
        }

        if(irCall.typeArgumentsCount > 0) {
            val implicitTypeArguments = detectImplicitTypes && getSourceHelper(data).sourceElementIs(irCall) { call ->
                call.children.none { it is KtTypeArgumentList }
            }
            invocation.setActualTypeArguments<CtInvocation<Any>>(
                irCall.symbol.descriptor.typeParameters.map {
                    referenceBuilder.getNewTypeReference<Any>(irCall.getTypeArgument(it.index)!!).also {
                            res -> res.setImplicit(implicitTypeArguments)
                    }
                }
            )
        }
        if(detectInfix) {
            invocation.putKtMetadata(
                KtMetadataKeys.INVOCATION_IS_INFIX,
                KtMetadata.wrap(helper.isInfixCall(irCall, data))
            )
        }
        if(irCall.origin == IrStatementOrigin.INVOKE) {
            invocation.putKtMetadata(
                KtMetadataKeys.INVOKE_AS_OPERATOR,
                KtMetadata.wrap(true)
            )
        }

        return invocation.definitely()
    }

    override fun visitCall(expression: IrCall, data: ContextData): DefiniteTransformResult<CtElement> {
        val nonInvocationResult = specialInvocation(expression, data)
        if(nonInvocationResult.isDefinite) return nonInvocationResult as DefiniteTransformResult<CtElement>
        return createInvocation(expression, data)
    }

    private fun visitBinaryOperator(irCall: IrCall, data: ContextData): DefiniteTransformResult<CtBinaryOperator<*>> {
        val tempLhs: IrExpression
        val tempRhs: IrExpression
        if(irCall.valueArgumentsCount == 2) {
            tempLhs = irCall.getValueArgument(0)!!
            tempRhs = irCall.getValueArgument(1)!!
        } else if(irCall.valueArgumentsCount == 1) {
            tempLhs = irCall.dispatchReceiver!!
            tempRhs = irCall.getValueArgument(0)!!
        } else {
            val receiver = irCall.dispatchReceiver
            if(receiver is IrCall)
                return visitBinaryOperator(receiver, data)
            else
                throw SpoonIrBuildException("Unable to get operands of binary operator call")
        }
        val opKind = OperatorHelper.originToBinaryOperatorKind(irCall.origin!!)
        val (irLhs, irRhs) = OperatorHelper.getOrderedBinaryOperands(tempLhs, tempRhs, opKind)
        val lhs = irLhs.accept(this, data).resultUnsafe
        val rhs = irRhs.accept(this, data).resultUnsafe

        val ctOp = core.createBinaryOperator<Any>()

        ctOp.setLeftHandOperand<CtBinaryOperator<Any>>(expressionOrWrappedInStatementExpression(lhs))
        ctOp.setRightHandOperand<CtBinaryOperator<Any>>(expressionOrWrappedInStatementExpression(rhs))
        ctOp.setType<CtBinaryOperator<Any>>(referenceBuilder.getNewTypeReference(irCall.type))

        ctOp.putKtMetadata(
            KtMetadataKeys.KT_BINARY_OPERATOR_KIND,
            KtMetadata.wrap(opKind)
        )
        return ctOp.definitely()
    }

    private fun visitUnaryOperator(irCall: IrCall, data: ContextData): DefiniteTransformResult<CtUnaryOperator<*>> {
        val ctOp = core.createUnaryOperator<Any>()
        val operand = irCall.dispatchReceiver!!.accept(this, data).resultUnsafe
        ctOp.setOperand<CtUnaryOperator<*>>(operand as CtExpression<Any>)
        ctOp.setKind<CtUnaryOperator<*>>(OperatorHelper.originToUnaryOperatorKind(irCall.origin!!))
        ctOp.setType<CtUnaryOperator<*>>(referenceBuilder.getNewTypeReference(irCall.type))
        return ctOp.definitely()
    }

    private fun visitForLoop(outerBlock: IrBlock, data: ContextData): DefiniteTransformResult<CtForEach> {
        val ctForEach = core.createForEach()
        val iterable = (outerBlock.statements[0] as IrVariable).initializer!!.accept(this, data).resultUnsafe
        val innerBlock = (outerBlock.statements[1] as IrWhileLoop).body as IrBlock
        val variables = innerBlock.statements.takeWhile { it is IrVariable }
        val context = Destruct(data)
        val variable = if(variables.size > 1) {
            val components = variables.drop(1).map { visitVariable(it as IrVariable, context).resultSafe }
            components.toDestructuredVariable()
        } else {
            visitVariable(variables[0] as IrVariable, data).resultSafe
        }

        val body = innerBlock.statements[variables.size].accept(this, data).resultUnsafe
        ctForEach.setVariable<CtForEach>(variable)
        ctForEach.setExpression<CtForEach>(iterable as CtExpression<*>)
        ctForEach.setBody<CtForEach>(body.blockOrSingleStatementBlock())
        return ctForEach.definitely()
    }

    private fun checkForCompositeElement(block: IrBlock, data: ContextData): TransformResult<CtElement> {
        if(block.origin == IrStatementOrigin.FOR_LOOP) {
            return visitForLoop(block, data)
        }
        if(block.origin in INCREMENT_DECREMENT_OPERATORS) {
            val setVar = block.statements.firstIsInstanceOrNull<IrSetVariable>()?.symbol
            val operand: CtExpression<Any> = if(setVar == null) {
                val call = block.statements.firstIsInstance<IrBlock>().
                statements.firstIsInstance<IrCall>()
                specialInvocation(call, data).resultUnsafe as CtExpression<Any>
            } else {
                createVariableWrite(null,
                    referenceBuilder.getNewVariableReference<Any>(setVar.descriptor))
            }
            val ctUnaryOp = core.createUnaryOperator<Any>()
            ctUnaryOp.setOperand<CtUnaryOperator<*>>(operand)
            ctUnaryOp.setKind<CtUnaryOperator<*>>(OperatorHelper.originToUnaryOperatorKind(block.origin!!))
            ctUnaryOp.setType<CtUnaryOperator<*>>(referenceBuilder.getNewTypeReference(block.type))
            return ctUnaryOp.definitely()
        }
        if(block.origin in AUGMENTED_ASSIGNMENTS) {
            return createAugmentedAssignmentOperator(block, block.origin!!, data).definitely()
        }
        return TransformResult.nothing()
    }

    override fun visitWhileLoop(loop: IrWhileLoop, data: ContextData): DefiniteTransformResult<CtWhile> {
        val ctWhile = core.createWhile()
        val condition = loop.condition.accept(this, data).resultUnsafe as CtExpression<Boolean>
        val body = loop.body!!.accept(this,data).resultUnsafe.blockOrSingleStatementBlock()
        ctWhile.setLoopingExpression<CtWhile>(condition)
        ctWhile.setBody<CtWhile>(body)
        return ctWhile.definitely()
    }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: ContextData): DefiniteTransformResult<CtElement> {
        val ctDo = factory.Core().createDo()
        val condition = loop.condition.accept(this, data).resultUnsafe as CtExpression<Boolean>

        val body = if(loop.body is IrComposite) { // Empty body becomes composite for some reason
            core.createBlock<Any>()
        } else {
            loop.body!!.accept(this, data).resultUnsafe.blockOrSingleStatementBlock()
        }
        ctDo.setLoopingExpression<CtDo>(condition)
        ctDo.setBody<CtDo>(body)
        return ctDo.definitely()
    }

    private fun visitBlock(expression: IrBlock, data: ContextData, skipIndices: List<Int>): DefiniteTransformResult<CtElement> {
        val statements = ArrayList<CtStatement>()
        for((i, statement) in expression.statements.withIndex()) {
            if(i in skipIndices) continue
            val ctElement = statement.accept(this, data).resultOrNull ?: continue
            val ctStmt: CtStatement = when(ctElement) {
                is CtMethod<*> -> {
                    ctElement.wrapLocalMethod()
                }
                is CtStatement -> ctElement
                else -> statementOrWrappedInImplicitReturn(ctElement)
            }
            statements.add(ctStmt)
        }

        val ctBlock = core.createBlock<Any>()
        ctBlock.setStatements<CtBlock<*>>(statements)
        ctBlock.putKtMetadata(KtMetadataKeys.KT_STATEMENT_TYPE,
            KtMetadata.wrap(referenceBuilder.getNewTypeReference<CtBlock<*>>(expression.type)))
        return ctBlock.definitely()

    }

    override fun visitBlock(expression: IrBlock, data: ContextData): DefiniteTransformResult<CtElement> {
        val composite = checkForCompositeElement(expression, data).resultOrNull
        if(composite != null) return composite.definitely()
        return visitBlock(expression, data, emptyList())
    }

    private fun createAugmentedAssignmentOperator(
            expression: IrExpression,
            origin: IrStatementOrigin,
            data: ContextData
    ): CtOperatorAssignment<*,*> {
        val (irLhs, irRhs) = OperatorHelper.getAugmentedAssignmentOperands(expression)
        val ctAssignmentOp = core.createOperatorAssignment<Any,Any>()
        val lhs = expressionOrWrappedInStatementExpression(irLhs.accept(this, data).resultUnsafe)
        val rhs = expressionOrWrappedInStatementExpression(irRhs.accept(this, data).resultUnsafe)
        ctAssignmentOp.setKind<CtOperatorAssignment<Any,Any>>(
            OperatorHelper.originToBinaryOperatorKind(origin).toJavaAssignmentOperatorKind()
        )
        ctAssignmentOp.setAssigned<CtOperatorAssignment<Any,Any>>(lhs)
        ctAssignmentOp.setAssignment<CtOperatorAssignment<Any,Any>>(rhs)
        ctAssignmentOp.setType<CtOperatorAssignment<Any,Any>>(referenceBuilder.getNewTypeReference(expression.type))
        return ctAssignmentOp
    }

    override fun visitSetVariable(expression: IrSetVariable, data: ContextData): TransformResult<CtElement> {
        if(expression.origin in AUGMENTED_ASSIGNMENTS) {
            return createAugmentedAssignmentOperator(expression, expression.origin!!, data).definitely()
        }

        if(expression.origin != IrStatementOrigin.EQ) TODO()
        val lhs = createVariableWrite(null, referenceBuilder.getNewVariableReference<Any>(expression.symbol.descriptor))
        val rhs = expression.value.accept(this, data).resultUnsafe as CtExpression<Any>
        return createAssignment(lhs, rhs).also {
            it.setType<CtExpression<*>>(referenceBuilder.getNewTypeReference(expression.type))
        }.definitely()
    }

    @Suppress("UNCHECKED_CAST")
    private fun createVariableWrite(receiver: CtExpression<*>?, variableRef: CtReference) = when (variableRef) {
        is CtLocalVariableReference<*> ->
            factory.Core().createVariableWrite<Any>().also {
                it.setVariable<CtVariableAccess<Any>>(variableRef as CtLocalVariableReference<Any>)
            }
        is CtFieldReference<*> -> {
            factory.Core().createFieldWrite<Any>().also {
                it.setVariable<CtVariableAccess<Any>>(variableRef as CtFieldReference<Any>)
                it.setTarget<CtTargetedExpression<Any, CtExpression<*>>>(receiver)
            }
        }
        is CtParameterReference<*> -> {
            factory.Core().createVariableWrite<Any>().also {
                it.setVariable<CtVariableAccess<Any>>(variableRef as CtParameterReference<Any>)
            }
        }
        else -> throw SpoonException("Unexpected expression ${variableRef::class.simpleName}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun createVariableRead(variableRef: CtReference) = when(variableRef) {
        is CtFieldReference<*> -> {
            factory.Core().createFieldRead<Any>().also {
                it.setVariable<CtVariableRead<Any>>(variableRef as CtVariableReference<Any>)
            }
        }
        is CtParameterReference<*> -> {
            factory.Core().createVariableRead<Any>().also {
                it.setVariable<CtVariableRead<Any>>(variableRef as CtVariableReference<Any>)
            }
        }
        is CtLocalVariableReference<*> -> {
            factory.Core().createVariableRead<Any>().also {
                it.setVariable<CtVariableRead<Any>>(variableRef as CtVariableReference<Any>)
            }
        }
        is CtSuperAccess<*> -> {
            variableRef
        }
        else -> throw SpoonIrBuildException("Unexpected reference for variable read ${variableRef::class.simpleName}")
    }


    private fun visitPropertyAccess(irCall: IrCall, data: ContextData): DefiniteTransformResult<CtVariableAccess<*>> {
        val descriptor = irCall.symbol.descriptor as PropertyGetterDescriptor
        val variable = referenceBuilder.getNewVariableReference<Any>(descriptor.correspondingProperty)
        val target = getReceiver(irCall, data)
        val fieldRead = core.createFieldRead<Any>()
        fieldRead.setVariable<CtFieldRead<Any>>(variable)
        if(target != null) fieldRead.setTarget<CtFieldRead<Any>>(target as CtExpression<*>)
        return fieldRead.definitely()
    }

    override fun visitExpressionBody(body: IrExpressionBody, data: ContextData):
            TransformResult<CtElement> {
        return body.expression.accept(this, data)
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall, data: ContextData): TransformResult<CtElement> {
        if(expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
            return expression.argument.accept(this, data)
        }
        return super.visitTypeOperator(expression, data)
    }

    override fun visitComposite(expression: IrComposite, data: ContextData): TransformResult<CtElement> {
        val context = Destruct(data)
        val components = expression.statements.drop(1).map { visitVariable(it as IrVariable, context).resultSafe }

        val placeHolder = components.toDestructuredVariable()
        placeHolder.setDefaultExpression<CtLocalVariable<Any>>(
            (expression.statements[0] as IrVariable).initializer!!.accept(this, data).resultUnsafe as CtExpression<Any>
        )
        placeHolder.addModifiersAsMetadata(IrToModifierKind.fromVariable(expression.statements[1] as IrVariable))
        return placeHolder.definitely()
    }

    override fun visitGetValue(expression: IrGetValue, data: ContextData): TransformResult<CtElement> {
        val symbol = expression.symbol
        if(symbol is IrValueSymbol) {
            val descriptor = symbol.descriptor
            if(symbol is IrValueParameterSymbol && descriptor is ReceiverParameterDescriptor) {
                return visitThisReceiver(expression, data).definitely()
            }
            if(symbol is IrVariableSymbol && descriptor is IrTemporaryVariableDescriptor) {
                if(descriptor.name.asString().matches("tmp\\d+_this".toRegex()))
                    return visitThisReceiver(expression, data).definitely()
            }
        }

        val varAccess = createVariableRead(referenceBuilder.getNewVariableReference<Any>(expression))

        return varAccess.definitely()
    }

    private fun getReceiver(irCall: IrCall, data: ContextData): CtElement? {
        if(irCall.superQualifierSymbol != null) return visitSuperTarget(irCall.superQualifierSymbol!!)
        return helper.getReceiver(irCall)?.accept(this, data)?.resultOrNull
    }

    private fun visitThisReceiver(irGetValue: IrGetValue, data: ContextData): CtThisAccess<*> {
        val implicit = helper.isImplicitThis(irGetValue, data.file)
        return factory.Code().createThisAccess<Any>(
            referenceBuilder.getNewTypeReference(irGetValue.type),
            implicit
        )
    }

    private fun visitSuperTarget(symbol: IrClassSymbol): CtSuperAccess<*> {
        val superAccess = core.createSuperAccess<Any>()
        superAccess.setType<CtSuperAccess<*>>(referenceBuilder.getNewTypeReference(
            symbol.descriptor
        ))
        superAccess.setImplicit<CtSuperAccess<*>>(false)
        return superAccess
    }

    override fun visitGetObjectValue(expression: IrGetObjectValue, data: ContextData): MaybeTransformResult<CtElement> {
        if(getSourceHelper(data).sourceTextIs(expression) { text -> text == "return" }) {
            return TransformResult.nothing()
        }
        val typeAccess = core.createTypeAccess<Any>()
        typeAccess.setAccessedType<CtTypeAccess<Any>>(referenceBuilder.getNewTypeReference<Any>(expression.type))
        return maybe(typeAccess)
    }

    override fun visitReturn(expression: IrReturn, data: ContextData): DefiniteTransformResult<CtElement> {
        val ctReturn = core.createReturn<Any>()
        checkLabelOfStatement(expression, ctReturn, data)
        val targetLabel = getSourceHelper(data).returnTargetLabelOrNull(expression)
        if(targetLabel != null) {
            /*
            'label1@ return@label2' is valid in Kotlin, but CtReturn only has one label inherited from CtStatement.
             We use metadata label for the target label, and the CtStatement label is reserved for the prefix label
             */
            ctReturn.putKtMetadata(KtMetadataKeys.LABEL, KtMetadata.wrap(targetLabel))
        }
        val transformResult = expression.value.accept(this, data).resultOrNull
        if(transformResult != null) {
            ctReturn.setReturnedExpression<CtReturn<Any>>(
                expressionOrWrappedInStatementExpression(transformResult)
            )
        }
        if(expression.endOffset == expression.startOffset)
            ctReturn.setImplicit<CtReturn<*>>(true)
        return ctReturn.definitely()
    }

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

    private fun List<CtLocalVariable<*>>.toDestructuredVariable(): CtLocalVariable<Any> {
        val placeHolder = core.createLocalVariable<Any>()
        placeHolder.setImplicit<CtElement>(true)
        placeHolder.putKtMetadata(KtMetadataKeys.IS_DESTRUCTURED, KtMetadata.wrap(true))
        placeHolder.putMetadata<CtElement>(KtMetadataKeys.COMPONENTS, this)
        return placeHolder
    }

    private fun CtElement.blockOrSingleStatementBlock(): CtBlock<*> = when(this) {
        is CtBlock<*> -> this
        else -> {
            val block = core.createBlock<Any>()
            if(this is CtExpression<*>) {
                block.putKtMetadata(KtMetadataKeys.KT_STATEMENT_TYPE,
                    KtMetadata.wrap(this.type)
                    )
            }
            block.addStatement<CtBlock<*>>(statementOrWrappedInImplicitReturn(this))
            block.setImplicit<CtBlock<*>>(true)
        }
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

    private fun CtMethod<*>.wrapLocalMethod(): CtClass<Any> {
        val wrapperClass = factory.Core().createClass<Any>()
        wrapperClass.setImplicit<CtClass<Any>>(true)
        wrapperClass.setSimpleName<CtClass<*>>("<local>")
        wrapperClass.addMethod<Any, CtClass<Any>>(this as CtMethod<Any>)
        return wrapperClass
    }
}
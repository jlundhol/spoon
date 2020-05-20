package spoon.kotlin.compiler

import spoon.kotlin.ktMetadata.KtMetadataKeys
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import spoon.reflect.reference.CtPackageReference
import spoon.reflect.reference.CtReference
import spoon.reflect.reference.CtTypeReference

internal class ReferenceBuilder(val firTreeBuilder: FirTreeBuilder) {
    private val msgCollector = PrintingMsgCollector()
    fun <T> buildTypeReference(typeRef: FirResolvedTypeRef) : CtTypeReference<T> {
        val ctRef = firTreeBuilder.factory.Core().createTypeReference<T>()
        typeRef.type.classId?.packageFqName?.let<FqName,CtTypeReference<T>> {
            ctRef.setPackage(getPackageReference(it))
        }
        return ctRef
    }

    fun <T> buildTypeReference(typeRef: ConeClassLikeType) : CtTypeReference<T> {
        val ctRef = firTreeBuilder.factory.Core().createTypeReference<T>()
        typeRef.classId?.let<ClassId,CtTypeReference<T>> {
                ctRef.setPackage<CtTypeReference<T>>(getPackageReference(it.packageFqName))
                ctRef.setSimpleName(it.shortClassName.identifier)
             }
        return ctRef
    }


    fun <T> buildGenericTypeReference(typeRef: FirResolvedTypeRef) : CtTypeReference<T> {
        TODO()
    }

    fun <T> getNewTypeReference(symbol: FirClassSymbol<*>) : CtTypeReference<T> {
        val ref = firTreeBuilder.factory.Core().createTypeReference<T>()
        ref.setSimpleName<CtTypeReference<T>>(symbol.classId.shortClassName.identifier)
        return ref
    }

    fun <T> getNewTypeReference(symbol: FirClassLikeSymbol<*>, nullable : Boolean) : CtTypeReference<T> {
        val ref = firTreeBuilder.factory.Core().createTypeReference<T>()
        ref.setSimpleName<CtTypeReference<T>>(symbol.classId.shortClassName.identifier)
        ref.setPackage<CtTypeReference<T>>(getPackageReference(symbol.classId.packageFqName))
        ref.putMetadata<CtTypeReference<T>>(KtMetadataKeys.TYPE_REF_NULLABLE, nullable)
        return ref
    }

    fun <T> getNewTypeReference(typeRef: FirTypeRef) : CtTypeReference<T> {
        val ref = firTreeBuilder.factory.Core().createTypeReference<T>()
        val firType = typeRef.coneTypeSafe<ConeClassLikeType>()
        val symbol = firType?.lookupTag?.toSymbol(firTreeBuilder.file.session)
        if(firType != null && symbol != null ) {
            ref.setSimpleName<CtTypeReference<T>>(symbol.classId.shortClassName.identifier)
            ref.setPackage<CtTypeReference<T>>(getPackageReference(symbol.classId.packageFqName))
            ref.putMetadata<CtTypeReference<T>>(KtMetadataKeys.TYPE_REF_NULLABLE, firType.nullability.isNullable)
        } else {
            if(firType == null) {
                msgCollector.report(Message("Can't get ConeType for TypeRef $typeRef",MessageType.WARN))
            } else {
                msgCollector.report(Message("Can't get symbol for TypeRef $typeRef",MessageType.WARN))
            }
            ref.setSimpleName<CtTypeReference<T>>("ErrorType")
        }
        return ref
    }

    fun <T> getSimpleTypeReference(classId: ClassId) : CtTypeReference<T> {
        return firTreeBuilder.factory.Core().createTypeReference<T>().apply {
            setSimpleName<CtTypeReference<T>>(classId.shortClassName.identifier)
            setPackage(getPackageReference(classId.packageFqName))
        }
    }

    fun <T> setPackageOrDeclaringType(ref : CtTypeReference<T>, declaring : CtReference) {
        when(declaring) {
            is CtPackageReference -> ref.setPackage<CtTypeReference<T>>(declaring)
            is CtTypeReference<*> -> ref.setDeclaringType<CtTypeReference<T>>(declaring)
        }
    }

    fun getPackageReference(fqName : FqName) : CtPackageReference {
        if(fqName.isRoot) {
            return firTreeBuilder.factory.Package().topLevel()
        }

        return firTreeBuilder.factory.Core().createPackageReference().apply {
            setSimpleName<CtPackageReference>(fqName.shortName().identifier)
        }
    }

}
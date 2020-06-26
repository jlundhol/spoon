package spoon.test

import spoon.kotlin.ktMetadata.KtMetadataKeys
import spoon.kotlin.reflect.KtModifierKind
import spoon.kotlin.reflect.visitor.printing.DefaultKotlinPrettyPrinter
import spoon.kotlin.reflect.visitor.printing.DefaultPrinterAdapter
import spoon.reflect.declaration.CtType
import spoon.reflect.declaration.CtVariable

object TestUtils {
    val pp = DefaultKotlinPrettyPrinter(DefaultPrinterAdapter())
}

fun CtType<*>.getMethodByName(method: String) = getMethodsByName(method)[0]
fun CtVariable<*>.getKtModifiers() = getMetadata(KtMetadataKeys.KT_MODIFIERS) as Set<KtModifierKind>
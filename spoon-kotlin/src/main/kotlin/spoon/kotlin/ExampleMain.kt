package spoon.kotlin

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import spoon.kotlin.compiler.SpoonKtEnvironment
import spoon.kotlin.compiler.ir.Empty
import spoon.kotlin.compiler.ir.IrGenerator
import spoon.kotlin.compiler.ir.IrTreeBuilder
import spoon.kotlin.compiler.ir.PsiImportBuilder
import spoon.kotlin.reflect.visitor.printing.DefaultKotlinPrettyPrinter
import spoon.kotlin.reflect.visitor.printing.DefaultPrinterAdapter
import spoon.reflect.CtModel
import spoon.reflect.declaration.CtCompilationUnit
import spoon.reflect.factory.FactoryImpl
import spoon.support.DefaultCoreFactory
import spoon.support.StandardEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Example runner of SpoonKt.
 * Requires dependencies to be accessible as JARs at libLocation. They can be downloaded using the target project's
 * build tool. Such as "mvn dependency:copy-dependencies" for maven or adding a task in gradle:
   tasks.register<Copy>("downloadJars") {
     from(configurations.kotlinCompilerClasspath, configurations.kotlinCompilerPluginClasspath)
     into("lib")
   }
 */
class ExampleMain(val rootDir: String, mainFolder: String? = "src/main", mainSubfolder: String = "kotlin", libLocation: String = "lib") {
    private val classPath: String

    init {
        val lib = File("$rootDir/$libLocation")
        val deps = lib.listFiles()
        classPath = deps?.joinToString(separator = ";") { it.absolutePath } ?: ""
        println("Classpath: ")
        for(d in deps) {
            println(d.absolutePath)
        }
    }

    val dir = if(mainFolder == null) rootDir else "$rootDir/$mainFolder"
    val args = K2JVMCompilerArguments().also {
        it.classpath = classPath
    }

    val setup: SpoonKtEnvironment
    init {
       setup = SpoonKtEnvironment(
           listOf(File("$dir/$mainSubfolder")),
            "spoonkt testing",
            args
        )
    }

    private fun printProgress(progress: Int, total: Int) {
        val length = 30.0
        val leftLength = ((progress.toDouble()/total.toDouble())*length).toInt()
        val left = "#".repeat(leftLength)
        val right = "-".repeat(length.toInt()-leftLength)
        print("|$left$right| $progress/$total\r")
        if(progress == total) println()
        System.out.flush()
    }

    fun run() {
        val factory = FactoryImpl(DefaultCoreFactory(), StandardEnvironment())
        val g = IrGenerator(setup)
        val buildStart = System.nanoTime()
        print("Building IR module (${g.environment.getSourceFiles().size} files)")
        val (irModule, context) = g.generateIrWithContext()
        val buildFinish = System.nanoTime()
        println("...Done (${TimeUnit.NANOSECONDS.toMillis(buildFinish-buildStart)} ms)")
        val builder = IrTreeBuilder(factory, context)
        val cus = ArrayList<CtCompilationUnit>()
        var progress = 0
        val numFiles = irModule.files.size
        println("Starting build..")
        println("$numFiles files to build")

        val startTime = System.nanoTime()
        for(f in irModule.files) {
            printProgress(progress, numFiles)
            cus.add(builder.visitFile(f, Empty(f)).resultSafe)
            progress += 1
        }
        printProgress(progress, numFiles)
        val importBuilder = PsiImportBuilder(factory)
        importBuilder.build(irModule.files.map { context.sourceManager.getKtFile(it)!! })
        val finishTime = System.nanoTime()
        val buildTimeMs = TimeUnit.NANOSECONDS.toMillis(finishTime-startTime)
        println("Build finished in ${buildTimeMs}ms")
        builder.factory.model.setBuildModelIsFinished<CtModel>(true);
        val pp = DefaultKotlinPrettyPrinter(DefaultPrinterAdapter())

        println("Printing to $rootDir/spooned/")
        val printStart = System.nanoTime()
        for(c in cus) {
            val file = c.file.absolutePath.replace('\\', '/')
            val newFile = File("$rootDir/spooned/${file.substring(rootDir.length)}")
            newFile.parentFile.mkdirs()
            newFile.createNewFile()
            newFile.writeText(pp.prettyprint(c))
        }
        val printFinish = System.nanoTime()
        println("Printing finished in ${TimeUnit.NANOSECONDS.toMillis(printFinish-printStart)}ms")
        println("Done")
    }
}

fun main() {
    ExampleMain("<path-to-project-root>").run()
}
package org.jetbrains.kotlin.backend.konan

import llvm.LLVMLinkModules2
import llvm.LLVMModuleRef
import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryWriter
import org.jetbrains.kotlin.backend.konan.library.impl.buildLibrary
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.backend.konan.util.getValueOrNull
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.target.CompilerOutputKind

internal interface CompilerOutputProducer {
    val context: Context

    fun produce()
}

internal class BitcodeProducer(override val context: Context) : CompilerOutputProducer {
    override fun produce() {
        val llvmModule = context.llvmModule!!

        val output = context.config.outputFile
        LLVMWriteBitcodeToFile(llvmModule, output)
    }
}


// TODO: library extension should be platform dependent
internal class LibraryProducer(override val context: Context) : CompilerOutputProducer {

    private val phaser = PhaseManager(context)
    lateinit var library: KonanLibraryWriter

    override fun produce() {
        val llvmModule = context.llvmModule!!
        val config = context.config.configuration
        val libraryName = context.config.moduleId

        val bitcode  = produceLibrary(context, config, llvmModule)

        phaser.phase(KonanPhase.OBJECT_FILES) {
            // stubs
            context.config.nativeLibraries.forEach {
                val stubs = compileStaticLibrary(context, listOf(it), File(it).name + ".a")
                library.addIncludedBinary(stubs)
            }
            val staticLib = compileStaticLibrary(context, listOf(bitcode),libraryName + ".a")
            library.addIncludedBinary(staticLib)
        }

        library.commit()
    }

    private fun produceLibrary(context: Context, config: CompilerConfiguration, llvmModule: LLVMModuleRef): String {
        val output = context.config.outputName
        val neededLibraries = context.llvm.librariesForLibraryManifest
        val libraryName = context.config.moduleId
        val abiVersion = context.config.currentAbiVersion
        val target = context.config.targetManager.target
        val nopack = config.getBoolean(KonanConfigKeys.NOPACK)
        val manifest = config.get(KonanConfigKeys.MANIFEST_FILE)

        library = buildLibrary(
                context.config.nativeLibraries,
                context.config.includeBinaries,
                neededLibraries,
                context.serializedLinkData!!,
                abiVersion,
                target,
                output,
                libraryName,
                llvmModule,
                nopack,
                manifest,
                context.escapeAnalysisResult.getValueOrNull()?.build()?.toByteArray(),
                context.dataFlowGraph)
        return library.mainBitcodeFileName
    }
}

internal class ProgramProducer(override val context: Context) : CompilerOutputProducer {

    private val phaser = PhaseManager(context)

    override fun produce() {
        val llvmModule = context.llvmModule!!
        val config = context.config.configuration
        val tempFiles = context.config.tempFiles

        val produce = config.get(KonanConfigKeys.PRODUCE)
                ?: throw IllegalArgumentException("Unknown produce type")

        val bitcodeFiles = if (produce == CompilerOutputKind.DYNAMIC) {
            produceCAdapterBitcode(
                    context.config.clang,
                    tempFiles.cAdapterHeaderName,
                    tempFiles.cAdapterCppName,
                    tempFiles.cAdapterBitcodeName)
            listOf(tempFiles.cAdapterBitcodeName)
        } else {
            emptyList()
        }
        val programBitcode = produceProgram(context, tempFiles, llvmModule, bitcodeFiles)

        lateinit var objectFiles: List<ObjectFile>
        phaser.phase(KonanPhase.OBJECT_FILES) {
            objectFiles = compileObjectFiles(context, listOf(programBitcode))
        }
        phaser.phase(KonanPhase.LINK_STAGE) {
            LinkStage(BackendSetup(context)).linkStage(objectFiles)
        }
    }

    private fun produceProgram(context: Context, tempFiles: TempFiles, llvmModule: LLVMModuleRef,
                               generatedBitcodeFiles: List<String> = emptyList()): String {
        val nativeLibraries = context.config.nativeLibraries +
                        context.config.defaultNativeLibraries +
                        generatedBitcodeFiles

        PhaseManager(context).phase(KonanPhase.BITCODE_LINKER) {
            for (library in nativeLibraries) {
                val libraryModule = parseBitcodeFile(library)
                val failed = LLVMLinkModules2(llvmModule, libraryModule)
                if (failed != 0) {
                    throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
                }
            }
        }
        val output = tempFiles.nativeBinaryFileName
        LLVMWriteBitcodeToFile(llvmModule, output)
        return output
    }
}
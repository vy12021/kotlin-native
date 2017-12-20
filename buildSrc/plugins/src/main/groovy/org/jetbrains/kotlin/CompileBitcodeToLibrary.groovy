package org.jetbrains.kotlin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction


class CompileBitcodeToLibrary extends DefaultTask {
    private String name = "main"
    private String target = "host"

    private List<String> llcArgs = []

    @InputFile
    File getBitcodeFile() {
        return new File(getTargetDir(), "${name}.bc")
    }

    @OutputFile
    File getOutFile() {
        return new File(getTargetDir(), "${name}.a")
    }

    File getObjectFile() {
        return new File(getTargetDir(), "${name}.o")
    }

    private File getTargetDir() {
        return new File(project.buildDir, target)
    }

    private File getObjDir() {
        return new File(getTargetDir(), name)
    }

    String getTarget() {
        return target
    }

    List<String> getLlcArgs() {
        return llcArgs
    }

    void name(String value) {
        name = value
    }

    void target(String value) {
        target = value
    }

    void llcArgs(String... args) {
        llcArgs.addAll(args)
    }


    @TaskAction
    void compile() {
        List<String> llcArgs = this.getLlcArgs()
        File objDir = this.getObjDir()
        objDir.mkdirs()

        project.exec {
            executable "$project.llvmDir/bin/llc"
            args '-function-sections', '-O3', '-filetype=obj'
            args llcArgs
            args bitcodeFile
            args '-o', objectFile
        }
        project.exec {
            executable "$project.llvmDir/bin/llvm-ar"
            args 'rcs'
            args outFile
            args objectFile
        }
    }
}

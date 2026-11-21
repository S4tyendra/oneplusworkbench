package dev.satya.workbench.sys

object Root {
    data class Result(val code: Int, val out: String)

    fun run(vararg cmds: String): Result = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            for (c in cmds) { w.write(c); w.write("\n") }
            w.write("exit\n")
        }
        val out = p.inputStream.bufferedReader().readText()
        Result(p.waitFor(), out)
    } catch (t: Throwable) {
        Result(-1, t.toString())
    }

    fun available(): Boolean = run("id").out.contains("uid=0")
}

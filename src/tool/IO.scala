package matching.tool

import io.Source
import java.nio.file.{Paths, Files}
import java.io.PrintWriter

object IO {
  def loadFile(fileName: String): Source = {
    Source.fromFile(fileName)
  }

  def createFile(fileName: String, force: Boolean = false): File = {
    val file = Paths.get(fileName)
    if (force) Files.deleteIfExists(file)
    if (Files.notExists(file)) {
      Files.createFile(file)
    } else throw new Exception(s"file ${fileName} already exists")
    new File(fileName)
  }

  def createDirectory(dirName: String): Unit = {
    val dir = Paths.get(dirName)
    if (Files.notExists(dir)) {
      Files.createDirectory(dir)
    } else throw new Exception(s"directory ${dirName} already exists")
  }

  def escape(a: Any): String = {
    a match {
      case '\\' => "\\\\"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ' ' => "\u2423"
      case _ if a.toString.matches("""\p{C}""") =>
        s"\\x${a.toString.head.toHexString.padTo(2, "0").mkString}"
      case _ => a.toString
    }
  }
}

class File(fileName: String) extends PrintWriter(fileName) {
  var tab = " "*2

  def tabSize_=(size: Int): Unit = {
    tab = " "*size
  }
  def tabSize: Int = tab.length

  def write(s: String, depth: Int = 0): Unit = {
    write(s"${tab*depth}${s}")
  }

  def writeln(s: String = "", depth: Int = 0): Unit = {
    write(s"${tab*depth}${s}\n")
  }
}

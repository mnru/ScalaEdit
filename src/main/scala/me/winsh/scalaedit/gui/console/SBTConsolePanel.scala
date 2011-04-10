package me.winsh.scalaedit.gui.console

import scala.swing._
import javax.swing._
import de.mud.terminal.vt320
import de.mud.terminal.SwingTerminal
import java.awt.BorderLayout
import java.io.InputStreamReader
import java.io.BufferedReader
import de.mud.jta.plugin.Terminal
import de.mud.jta.PluginBus
import de.mud.jta.PluginLoader
import de.mud.jta.FilterPlugin
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import me.winsh.scalaedit.gui._
import me.winsh.scalaedit.gui.editor._
import me.winsh.scalaedit.api._
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import scala.util.matching.Regex

class SBTConsolePanel extends VT320ConsoleBase {

  val consoleType = SBTConsole

  private class SBTProcess extends InOutSource {

    private val process = {
      //Check if SBT is in properties dir

      val sbtJarName = "sbt-launch-0.7.5.jar"

      try {
        val javaFile = new File(new File(System.getProperty("java.home"), "bin"), "java")

        val javaPath = javaFile.exists match {
          case true => javaFile.getAbsolutePath
          case false => "java"
        }

        val sbtJarFile = new File(new File(Utils.propertiesDir, "bin"), sbtJarName)
        if (!sbtJarFile.exists) {

          putLine("Extracting " + sbtJarName + " to " + sbtJarFile.getAbsolutePath + " ...")

          new File(Utils.propertiesDir, "bin").mkdirs()
          sbtJarFile.createNewFile()

          val sbtStream = new BufferedInputStream(this.getClass.getResourceAsStream("/bin/" + sbtJarName))
          val outStream = new FileOutputStream(sbtJarFile)

          def readWrite(): Unit = sbtStream.read() match {
            case -1 => outStream.close()
            case r => {
              outStream.write(r)
              readWrite()
            }
          }

          readWrite()
          sbtStream.close()

        }
        Runtime.getRuntime().exec(Array("java", "-cp", sbtJarFile.getAbsolutePath, "xsbt.boot.Boot"))
      } catch {
        case e => {
          Utils.showErrorMessage(message = "<html>Could not lunch SBT. Trying to lunch command \"sbt\". <br/>" + e.getMessage())
          e.printStackTrace()
          Runtime.getRuntime().exec("sbt")
        }
      }

    }
    private val in = process.getInputStream
    private val err = process.getErrorStream
    private val out = process.getOutputStream
    private val onlyOneStreamLeft = new AtomicBoolean(false)

    private val readQueue = new ArrayBlockingQueue[Int](1024, false)

    val input = new InputStream {

      def readFromStream(in: InputStream) {

        in.read() match {
          case -1 if (onlyOneStreamLeft.get()) => readQueue.put(-1)
          case -1 => onlyOneStreamLeft.set(true)
          case v => {
            readQueue.put(v)
            readFromStream(in) //Recursive call
          }
        }
      }

      Utils.runInNewThread(() => readFromStream(in))
      Utils.runInNewThread(() => readFromStream(err))

      def read(): Int = readQueue.take()

    }

    val output = new OutputStream {
      def write(toWrite: Int) = { out.write(toWrite); out.flush() }
    }

  }

  private var sbtProcess: SBTProcess = null

  def inOutSource: InOutSource = {
    if (sbtProcess == null)
      sbtProcess = new SBTProcess()

    sbtProcess
  }

  def close() = {
    stop()
    true
  }

  private var toMatchOn = new StringBuffer()

  case object Beginning {

    val le = """(.*Compiling.*sources.*)\n""".r //"""(?s)(.*\[.*info.*]\s*Compiling.*sources\.\.\..*)""".r

    def unapply(line: String) = {

      try {
        val le(c) = line

        Some(Beginning)

      } catch {
        case _ => None
      }
    }
  }
  case object FirstErrorLine {

    val regexpLine1 = """.*error.*0m(.*):(\d*):(.*)\n""" //""".*\[.*e.*r.*r.*o.*r.*\]0m(.*):([^:]*):\s*(.*)\n"""
    val regexpLine2 = "" //""".*\[.*e.*r.*r.*o.*r.*\]\s(.*)\n"""
    val regexpLine3 = "" //""".*\[.*e.*r.*r.*o.*r.*\]\s(.*)\n.*"""

    val le = (regexpLine1 + regexpLine2 + regexpLine3).r

    def unapply(line: String) = {

      try {

        val le(sourceFileName, lineNumber, messagePart1) = line
        Some(sourceFileName, lineNumber.toInt, messagePart1)

      } catch {
        case e => None
      }
    }
  }

  case object End {

    val le = """(.*==\s*compile\s*==.*)\n""".r //"""(?s)(.*\[.*info.*]\s*Compiling.*sources\.\.\..*)""".r

    def unapply(line: String) = {

      try {
        val le(c) = line

        Some(End)

      } catch {
        case _ => None
      }
    }
  }

  var codeNotifications: List[CodeNotification] = Nil
  addInvokeOnConsoleOutput((in: Int) => {
    toMatchOn.append(in.toChar)
    //print(in.toChar)
    //See if it matches something biginning, error, warning, end
    if (in == 10) {

      toMatchOn.toString match {
        case Beginning(s) => {
          //println("B")

          EditorsPanel().notifyAboutClearCodeInfo()
          toMatchOn = new StringBuffer()
        } case FirstErrorLine(fileName, lineNumber, message) => {

          codeNotifications = Error(fileName, lineNumber, message) :: codeNotifications

          toMatchOn = new StringBuffer()
        } case End(s) => {

          EditorsPanel().notifyAboutCodeInfo(codeNotifications)

          codeNotifications = Nil
          toMatchOn = new StringBuffer()
        }
        case _ =>
      }

      toMatchOn = new StringBuffer()

    }
  })

}
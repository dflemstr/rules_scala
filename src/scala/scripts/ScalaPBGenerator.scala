package scripts

import io.bazel.rulesscala.jar.JarCreator
import io.bazel.rulesscala.io_utils.DeleteRecursively
import java.io.{ File, PrintStream }
import java.nio.file.{ Files, Path, Paths }
import scala.collection.mutable.Buffer
import io.bazel.rulesscala.worker.{ GenericWorker, Processor }
import scala.io.Source
import protocbridge.ProtocBridge
import scalapb.ScalaPbCodeGenerator
import com.trueaccord.scalapb.{Config, ScalaPBC, ScalaPbcException}

object ScalaPBWorker extends GenericWorker(new ScalaPBGenerator) {

  override protected def setupOutput(ps: PrintStream): Unit = {
    System.setOut(ps)
    System.setErr(ps)
    Console.setErr(ps)
    Console.setOut(ps)
  }

  def main(args: Array[String]) {
    try run(args)
    catch {
      case x: Exception =>
        x.printStackTrace()
        System.exit(1)
    }
  }
}

class ScalaPBGenerator extends Processor {
  def deleteDir(path: Path): Unit =
    try DeleteRecursively.run(path)
    catch {
      case e: Exception => ()
    }

  def processRequest(args: java.util.List[String]) {
    val jarOutput = args.get(0)
    val parsedProtoFiles = args.get(1).split(':').toList.map { rootAndFile =>
      val parsed = rootAndFile.split(',')
      val root = parsed(0)
      val file = if (root.isEmpty) {
        parsed(1)
      } else {
        parsed(1).substring(root.length + 1)
      }
      (file, Paths.get(root, file).toString)
    }
    // This will map the absolute path of a given proto file
    // to a relative path that does not contain the repo prefix.
    // This is to match the expected behavior of
    // proto_library and java_proto_library where proto files
    // can import other proto files using only the relative path
    val imports = parsedProtoFiles.map { case (relPath, absolutePath) =>
      s"-I${relPath}=${absolutePath}"
    }
    val protoFiles = parsedProtoFiles.map(_._2)
    val flagOpt = args.get(2) match {
      case "-" => None
      case s => Some(s.drop(2))
    }

    val tmp = Paths.get(Option(System.getProperty("java.io.tmpdir")).getOrElse("/tmp"))
    val scalaPBOutput = Files.createTempDirectory(tmp, "bazelscalapb")
    val flagPrefix = flagOpt.map(_ + ":").getOrElse("")
    val scalaPBArgs = s"--scala_out=${flagPrefix}${scalaPBOutput}" :: (imports ++ protoFiles)
    val config = ScalaPBC.processArgs(scalaPBArgs.toArray)
    val code = ProtocBridge.runWithGenerators(
      protoc = a => com.github.os72.protocjar.Protoc.runProtoc(config.version +: a.toArray),
      namedGenerators = Seq("scala" -> ScalaPbCodeGenerator),
      params = config.args)

    val dirsToDelete = Set(scalaPBOutput)

    try {
      if (!config.throwException) {
        JarCreator.buildJar(Array(jarOutput, scalaPBOutput.toString))
      } else {
        if (code != 0) {
          throw new ScalaPbcException(s"Exit with code $code")
        }
      }
    } finally {
      dirsToDelete.foreach { deleteDir(_) }
    }
  }
}

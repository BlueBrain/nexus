package ch.epfl.bluebrain.nexus.storage

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.storage.utils.{EitherValues, IOEitherValues, Randomness}
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.io.FileUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, Inspectors, OptionValues}

import scala.annotation.tailrec

class TarFlowSpec
    extends TestKit(ActorSystem("TarFlowSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOEitherValues
    with Randomness
    with EitherValues
    with OptionValues
    with Inspectors
    with BeforeAndAfterAll {

  val basePath = Files.createTempDirectory("tarflow")
  val dir1     = basePath.resolve("one")
  val dir2     = basePath.resolve("two")

  override def afterAll(): Unit = {
    super.afterAll()
    FileUtils.cleanDirectory(basePath.toFile)
    ()
  }

  type PathAndContent = (Path, String)

  "A TarFlow" should {

    Files.createDirectories(dir1)
    Files.createDirectories(dir2)

    def relativize(path: Path): String = basePath.getParent().relativize(path).toString

    "generate the byteString for a tar file correctly" in {
      val file1        = dir1.resolve("file1.txt")
      val file1Content = genString()
      val file2        = dir1.resolve("file3.txt")
      val file2Content = genString()
      val file3        = dir2.resolve("file3.txt")
      val file3Content = genString()
      val files        = List(file1 -> file1Content, file2 -> file2Content, file3 -> file3Content)
      forAll(files) { case (file, content) =>
        Source.single(ByteString(content)).runWith(FileIO.toPath(file)).futureValue
      }
      val byteString   = Directory.walk(basePath).via(TarFlow.writer(basePath)).runReduce(_ ++ _).futureValue
      val bytes        = new ByteArrayInputStream(byteString.toArray)
      val tar          = new TarArchiveInputStream(bytes)

      @tailrec def readEntries(
          tar: TarArchiveInputStream,
          entries: List[PathAndContent] = Nil
      ): List[PathAndContent] = {
        val entry = tar.getNextTarEntry
        if (entry == null) entries
        else {
          val data = Array.ofDim[Byte](entry.getSize.toInt)
          tar.read(data)
          readEntries(tar, (Paths.get(entry.getName) -> ByteString(data).utf8String) :: entries)
        }
      }
      val directories = List(relativize(basePath) -> "", relativize(dir1) -> "", relativize(dir2) -> "")
      val untarred    = readEntries(tar).map { case (path, content) => path.toString -> content }
      val expected    = files.map { case (path, content) => relativize(path) -> content } ++ directories
      untarred should contain theSameElementsAs expected
    }
  }

}

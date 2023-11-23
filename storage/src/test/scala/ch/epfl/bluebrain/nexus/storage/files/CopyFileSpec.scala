package ch.epfl.bluebrain.nexus.storage.files

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.storage.StorageError.CopyOperationFailed
import ch.epfl.bluebrain.nexus.storage.files.CopyFiles.{parent, CopyBetween}
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

import java.util.UUID

class CopyFileSpec extends CatsEffectSuite {

  val myFixture: IOFixture[Path]                    = ResourceSuiteLocalFixture("create-temp-dir-fixture", Files[IO].tempDirectory)
  override def munitFixtures: List[IOFixture[Path]] = List(myFixture)
  lazy val tempDir: Path                            = myFixture()

  test("rollback by deleting file copies and directories if error thrown during a copy") {
    val bucket = genString
    val source = tempDir / genString / "file.txt"
    val dest1  = tempDir / genString / "file.txt"
    val dest2  = tempDir / genString / "file.txt"
    val dest3  = tempDir / genString / "file.txt"
    val files  = NonEmptyList.of(CopyBetween(source, dest1), CopyBetween(source, dest2), CopyBetween(source, dest3))

    for {
      _     <- givenAFileAtPath(source)
      _     <- givenAFileAtPath(dest2)
      error <- CopyFiles.copyAll(bucket, files).intercept[CopyOperationFailed]
      _     <- filesShouldNotExist(dest1, dest3, parent(dest1), parent(dest3))
      _     <- filesShouldExist(dest2)
    } yield assertEquals(error, CopyOperationFailed(bucket, source, dest2))
  }

  test("successfully perform multiple copies") {
    val source1Contents = genString
    val source2Contents = genString
    val bucket          = genString
    val source1         = tempDir / genString / "file.txt"
    val source2         = tempDir / genString / "file.txt"
    val dest1           = tempDir / genString / "file.txt"
    val dest2           = tempDir / genString / "file.txt"
    val dest3           = tempDir / genString / "file.txt"
    val files           = NonEmptyList.of(CopyBetween(source1, dest1), CopyBetween(source2, dest2), CopyBetween(source1, dest3))

    for {
      _ <- givenAFileAtPathWithContents(source1, source1Contents)
      _ <- givenAFileAtPathWithContents(source2, source2Contents)
      _ <- CopyFiles.copyAll(bucket, files)
      _ <- filesShouldExistWithContents(source1Contents, dest1, dest3)
      _ <- filesShouldExistWithContents(source2Contents, dest2)
    } yield ()
  }

  def genString: String = UUID.randomUUID().toString

  def filesShouldExistWithContents(contents: String, p: Path*): IO[Unit] =
    p.toList.traverse(Files[IO].readUtf8(_).compile.string.assertEquals(contents)).void

  def filesShouldNotExist(p: Path*): IO[Unit] = assertFileExistence(false, p: _*)

  def filesShouldExist(p: Path*): IO[Unit] = assertFileExistence(true, p: _*)

  def assertFileExistence(expected: Boolean, p: Path*): IO[Unit] =
    p.toList.traverse(Files[IO].exists(_).assertEquals(expected)).void

  def givenAFileAtPath(path: Path): IO[Unit] =
    Files[IO].createDirectories(Path.fromNioPath(path.toNioPath.getParent)) >> Files[IO].createFile(path)

  def givenAFileAtPathWithContents(path: Path, contents: String): IO[Unit] =
    givenAFileAtPath(path) >> fs2.Stream(contents).through(Files[IO].writeUtf8(path)).compile.drain
}

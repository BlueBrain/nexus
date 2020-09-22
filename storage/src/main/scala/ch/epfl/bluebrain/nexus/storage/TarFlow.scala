package ch.epfl.bluebrain.nexus.storage
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import akka.NotUsed
import akka.stream.scaladsl.{FileIO, Flow, Source}
import akka.util.ByteString
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarConstants}

/**
  * Akka stream flows for writing tar balls.
  */
object TarFlow {
  private val recordSize: Int   = 512
  private val eofBlockSize: Int = recordSize * 2

  private val terminalChunk =
    ByteString.newBuilder.putBytes(Array.ofDim[Byte](eofBlockSize)).result()

  private def headerBytes(basePath: Path, path: Path): ByteString = {
    val header  = new TarArchiveEntry(path.toFile, basePath.getParent.relativize(path).toString)
    val buffer  = Array.ofDim[Byte](recordSize)
    val builder = ByteString.newBuilder
    def appendHeader(header: TarArchiveEntry): Unit = {
      header.writeEntryHeader(buffer)
      builder ++= buffer
    }

    def padToBoundary(): Unit = {
      val mod = builder.length % recordSize
      if (mod != 0) builder ++= List.fill[Byte](recordSize - mod)(0)
    }

    val nameAsBytes = header.getName.getBytes(UTF_8)
    if (nameAsBytes.length > TarConstants.NAMELEN) {
      val longNameHeader = new TarArchiveEntry(TarConstants.GNU_LONGLINK, TarConstants.LF_GNUTYPE_LONGNAME)
      longNameHeader.setSize(nameAsBytes.length.toLong + 1L) // +1 for null
      appendHeader(longNameHeader)
      builder ++= nameAsBytes
      builder += 0
      padToBoundary()
    }
    appendHeader(header)
    padToBoundary()
    builder.result()
  }

  private def padToBoundary(path: Path): ByteString = {
    val mod = (Files.size(path) % recordSize).toInt
    if (mod == 0) ByteString.empty
    else ByteString(Array.fill[Byte](recordSize - mod)(0))
  }

  /**
    * Creates a Flow which deals with [[Path]]s
    * 1. Creates the ByteString value for the [[Path]] Tar Header and wraps it in a Source
    * 2. Creates a Source with the ByteString content of the [[Path]]
    * 3. Creates a ByteString with padding (0s) to fill the previous Source, when needed
    * The sources are concatenated: 1 --> 2 --> 3
    *
    * @param basePath the base directory from where to create the tarball
    * @return a Flow where the input is a [[Path]] and the output is a [[ByteString]]
    */
  def writer(basePath: Path): Flow[Path, ByteString, NotUsed] =
    Flow[Path]
      .flatMapConcat {
        case path if Files.isRegularFile(path) =>
          val headerSource  = Source.single(headerBytes(basePath, path))
          val paddingSource = Source.single(padToBoundary(path))
          headerSource.concat(FileIO.fromPath(path)).concat(paddingSource)
        case path                              =>
          Source.single(headerBytes(basePath, path))
      }
      .concat(Source.single(terminalChunk))
}

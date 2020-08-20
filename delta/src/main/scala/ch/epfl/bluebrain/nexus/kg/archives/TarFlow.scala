package ch.epfl.bluebrain.nexus.kg.archives

import java.nio.charset.StandardCharsets.UTF_8

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveSource.ArchiveSourceMetadata
import ch.epfl.bluebrain.nexus.kg.storage.AkkaSource
import org.apache.commons.compress.archivers.tar.TarArchiveEntry._
import org.apache.commons.compress.archivers.tar.TarConstants._
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarConstants}

/**
  * Akka stream flows for writing tar balls.
  */
object TarFlow extends GraphStage[FlowShape[Either[ArchiveSourceMetadata, ByteString], ByteString]] {

  private val in  = Inlet[Either[ArchiveSourceMetadata, ByteString]]("TarFlow.in")
  private val out = Outlet[ByteString]("TarFlow.out")

  override val shape = FlowShape(in, out)

  private val recordSize: Int   = 512
  private val eofBlockSize: Int = recordSize * 2

  private val terminalChunk =
    ByteString.newBuilder.putBytes(Array.ofDim[Byte](eofBlockSize)).result

  private[archives] def headerBytes(metadata: ArchiveSourceMetadata): ByteString = {
    val header  = new TarArchiveEntry(metadata.path, LF_NORMAL)
    header.setMode(DEFAULT_FILE_MODE)
    header.setSize(metadata.bytes)
    header.setModTime(metadata.time)
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

  private def padToBoundary(metadata: ArchiveSourceMetadata): ByteString = {
    val mod = (metadata.bytes % recordSize).toInt
    if (mod == 0) ByteString.empty
    else ByteString(Array.fill[Byte](recordSize - mod)(0))
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      private var current: Option[ArchiveSourceMetadata] = None

      setHandler(out, new OutHandler { override def onPull(): Unit = pull(in) })

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit =
            grab(in) match {
              case Left(metadata)    =>
                current.foreach(currentMeta => emit(out, padToBoundary(currentMeta)))
                current = Some(metadata)
                emit(out, headerBytes(metadata))
              case Right(byteString) =>
                push(out, byteString)
            }

          override def onUpstreamFinish(): Unit = {
            emit(out, terminalChunk)
            completeStage()
          }
        }
      )
    }

  /**
    * Creates a tar Source[ByteString] from a sequence of [[ArchiveSource]].
    * For every ArchiveSource, a tar header is added, following by the actual content and ended by a padding (if necessary)
    *
    * @param sources a sequence of [[ArchiveSource]]
    * @return a tar Source[ByteString]
    */
  def write(sources: Seq[ArchiveSource]): AkkaSource =
    sources
      .foldLeft(Source.empty[Either[ArchiveSourceMetadata, ByteString]]) { (acc, archive) =>
        acc.concatLazy(Source.single(Left(archive.metadata))).concatLazy(archive.source.map(Right(_)))
      }
      .via(TarFlow)

  // This is required instead of the regular concat because concat is eager: https://github.com/akka/akka/issues/23044
  implicit private class SourceLazyOps[E, M](private val src: Source[E, M]) extends AnyVal {
    def concatLazy[M1](src2: => Source[E, M1]): Source[E, NotUsed] =
      Source(List(() => src, () => src2)).flatMapConcat(_())
  }
}

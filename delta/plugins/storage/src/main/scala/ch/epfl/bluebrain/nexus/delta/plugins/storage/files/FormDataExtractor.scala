package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.PayloadTooLarge
import akka.http.scaladsl.model.{EntityStreamSizeException, ExceptionWithErrorInfo, HttpEntity, Multipart}
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.scaladsl.{Keep, Sink}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{InvalidMultipartFieldName, WrappedAkkaRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileDescription, FileRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import monix.bio.IO
import monix.execution.Scheduler

sealed trait FormDataExtractor {

  /**
    * Extracts the part with fieldName ''file'' from the passed ''entity'' MultiPart/FormData
    *
    * @param id        the file id
    * @param entity    the Miltipart/FormData payload
    * @param sizeLimit the file size limit to be uploaded, provided by the storage
    * @return the file description plus the stream of [[ByteString]] with the file content
    */
  def apply(id: Iri, entity: HttpEntity, sizeLimit: Long): IO[FileRejection, (FileDescription, AkkaSource)]
}
object FormDataExtractor {

  private val fieldName: String = "file"

  def apply(implicit
      uuidF: UUIDF,
      as: ActorSystem,
      sc: Scheduler,
      um: FromEntityUnmarshaller[Multipart.FormData]
  ): FormDataExtractor = new FormDataExtractor {
    override def apply(id: Iri, entity: HttpEntity, sizeLimit: Long): IO[FileRejection, (FileDescription, AkkaSource)] =
      IO.deferFuture(um(entity.withSizeLimit(sizeLimit)))
        .leftMap {
          case RejectionError(r)                  =>
            WrappedAkkaRejection(r)
          case Unmarshaller.NoContentException    =>
            WrappedAkkaRejection(RequestEntityExpectedRejection)
          case x: UnsupportedContentTypeException =>
            WrappedAkkaRejection(UnsupportedRequestContentTypeRejection(x.supported, x.actualContentType))
          case x: IllegalArgumentException        =>
            WrappedAkkaRejection(ValidationRejection(Option(x.getMessage).getOrElse(""), Some(x)))
          case x: ExceptionWithErrorInfo          =>
            WrappedAkkaRejection(MalformedRequestContentRejection(x.info.format(withDetail = false), x))
          case x                                  =>
            WrappedAkkaRejection(MalformedRequestContentRejection(Option(x.getMessage).getOrElse(""), x))
        }
        .flatMap { formData =>
          IO.fromFuture(
            formData.parts
              .mapAsync(parallelism = 1) {
                case part if part.name == fieldName =>
                  FileDescription(part.filename.getOrElse("file"), part.entity.contentType).runToFuture.map { desc =>
                    Some(desc -> part.entity.dataBytes)
                  }
                case part                           =>
                  part.entity.discardBytes().future.as(None)
              }
              .collect { case Some(values) => values }
              .toMat(Sink.headOption)(Keep.right)
              .run()
          ).leftMap {
            case ex: EntityStreamSizeException =>
              WrappedAkkaRejection(MalformedRequestContentRejection(PayloadTooLarge.reason, ex))
            case th                            =>
              WrappedAkkaRejection(MalformedRequestContentRejection(th.getMessage, th))
          }.flatMap(IO.fromOption(_, InvalidMultipartFieldName(id)))
        }
  }
}

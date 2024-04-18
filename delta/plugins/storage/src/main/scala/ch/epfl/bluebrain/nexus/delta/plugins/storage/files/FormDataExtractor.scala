package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`multipart/form-data`
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, MultipartUnmarshallers, Unmarshaller}
import akka.stream.scaladsl.{Keep, Sink}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.NotARejection
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName, WrappedAkkaRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

sealed trait FormDataExtractor {

  /**
    * Extracts the part with fieldName ''file'' from the passed ''entity'' MultiPart/FormData. Any other part is
    * discarded.
    *
    * @param id
    *   the file id
    * @param entity
    *   the Multipart/FormData payload
    * @param maxFileSize
    *   the file size limit to be uploaded, provided by the storage
    * @return
    *   the file metadata. plus the entity with the file content
    */
  def apply(
      id: Iri,
      entity: HttpEntity,
      maxFileSize: Long
  ): IO[UploadedFileInformation]
}

case class UploadedFileInformation(
    filename: String,
    suppliedContentType: ContentType,
    contents: BodyPartEntity
)

object FormDataExtractor {

  private val FileFieldName: String = "file"

  private val defaultContentType: ContentType.Binary = ContentTypes.`application/octet-stream`

  // Creating an unmarshaller defaulting to `application/octet-stream` as a content type
  @SuppressWarnings(Array("TryGet"))
  implicit private val um: FromEntityUnmarshaller[Multipart.FormData] =
    MultipartUnmarshallers
      .multipartUnmarshaller[Multipart.FormData, Multipart.FormData.BodyPart, Multipart.FormData.BodyPart.Strict](
        mediaRange = `multipart/form-data`,
        defaultContentType = defaultContentType,
        createBodyPart = (entity, headers) => Multipart.General.BodyPart(entity, headers).toFormDataBodyPart.get,
        createStreamed = (_, parts) => Multipart.FormData(parts),
        createStrictBodyPart =
          (entity, headers) => Multipart.General.BodyPart.Strict(entity, headers).toFormDataBodyPart.get,
        createStrict = (_, parts) => Multipart.FormData.Strict(parts)
      )

  def apply(
      mediaTypeDetector: MediaTypeDetectorConfig
  )(implicit as: ActorSystem): FormDataExtractor =
    new FormDataExtractor {
      implicit val ec: ExecutionContext = as.getDispatcher

      override def apply(
          id: Iri,
          entity: HttpEntity,
          maxFileSize: Long
      ): IO[UploadedFileInformation] = {
        for {
          formData <- unmarshall(entity, maxFileSize)
          fileOpt  <- extractFile(formData, maxFileSize)
          file     <- IO.fromOption(fileOpt)(InvalidMultipartFieldName(id))
        } yield file
      }

      private def unmarshall(entity: HttpEntity, sizeLimit: Long): IO[FormData] =
        IO.fromFuture(IO.delay(um(entity.withSizeLimit(sizeLimit)))).adaptError(onUnmarshallingError(_))

      private def onUnmarshallingError(th: Throwable): WrappedAkkaRejection = th match {
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

      private def extractFile(
          formData: FormData,
          maxFileSize: Long
      ): IO[Option[UploadedFileInformation]] = IO
        .fromFuture(
          IO(
            formData.parts
              .mapAsync(parallelism = 1)(extractFile)
              .collect { case Some(values) => values }
              .toMat(Sink.headOption)(Keep.right)
              .run()
          )
        )
        .adaptError {
          case _: EntityStreamSizeException =>
            FileTooLarge(maxFileSize)
          case NotARejection(th)            =>
            WrappedAkkaRejection(MalformedRequestContentRejection(th.getMessage, th))
        }

      private def extractFile(part: FormData.BodyPart): Future[Option[UploadedFileInformation]] = part match {
        case part if part.name == FileFieldName =>
          val filename    = part.filename.getOrElse("file")
          val contentType = detectContentType(filename, part.entity.contentType)

          Future(
            UploadedFileInformation(
              filename,
              contentType,
              part.entity
            ).some
          )
        case part                               =>
          part.entity.discardBytes().future.as(None)
      }

      private def detectContentType(filename: String, contentTypeFromRequest: ContentType) = {
        val bodyDefinedContentType = Option.when(contentTypeFromRequest != defaultContentType)(contentTypeFromRequest)

        val extensionOpt = FileUtils.extension(filename)

        def detectFromConfig = for {
          extension       <- extensionOpt
          customMediaType <- mediaTypeDetector.find(extension)
        } yield contentType(customMediaType)

        def detectAkkaFromExtension = extensionOpt.flatMap { e =>
          Try(MediaTypes.forExtension(e)).map(contentType).toOption
        }

        bodyDefinedContentType
          .orElse(detectFromConfig)
          .orElse(detectAkkaFromExtension)
          .getOrElse(contentTypeFromRequest)
      }

      private def contentType(mediaType: MediaType) = ContentType(mediaType, () => HttpCharsets.`UTF-8`)
    }
}

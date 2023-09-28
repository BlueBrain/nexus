package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`multipart/form-data`
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, MultipartUnmarshallers, Unmarshaller}
import akka.stream.scaladsl.{Keep, Sink}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{FileUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName, WrappedAkkaRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileDescription, FileRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO
import monix.execution.Scheduler

sealed trait FormDataExtractor {

  /**
    * Extracts the part with fieldName ''file'' from the passed ''entity'' MultiPart/FormData
    *
    * @param id
    *   the file id
    * @param entity
    *   the Miltipart/FormData payload
    * @param maxFileSize
    *   the file size limit to be uploaded, provided by the storage
    * @param storageAvailableSpace
    *   the remaining available space on the storage
    * @return
    *   the file description plus the entity with the file content
    */
  def apply(
      id: Iri,
      entity: HttpEntity,
      maxFileSize: Long,
      storageAvailableSpace: Option[Long]
  ): IO[FileRejection, (FileDescription, BodyPartEntity)]
}
object FormDataExtractor {

  private val fieldName: String = "file"

  private val defaultContentType: ContentType.Binary = ContentTypes.`application/octet-stream`

  // Creating an unmarshaller defaulting to `application/octet-stream` as a content type
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
  )(implicit uuidF: UUIDF, as: ActorSystem, sc: Scheduler): FormDataExtractor =
    new FormDataExtractor {
      override def apply(
          id: Iri,
          entity: HttpEntity,
          maxFileSize: Long,
          storageAvailableSpace: Option[Long]
      ): IO[FileRejection, (FileDescription, BodyPartEntity)] = {
        val sizeLimit = Math.min(storageAvailableSpace.getOrElse(Long.MaxValue), maxFileSize)
        IO.deferFuture(um(entity.withSizeLimit(sizeLimit)))
          .mapError {
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
                    val filename    = part.filename.getOrElse("file")
                    val contentType = detectContentType(filename, part.entity.contentType)
                    FileDescription(filename, contentType).runToFuture.map { desc =>
                      Some(desc -> part.entity)
                    }
                  case part                           =>
                    part.entity.discardBytes().future.as(None)
                }
                .collect { case Some(values) => values }
                .toMat(Sink.headOption)(Keep.right)
                .run()
            ).mapError {
              case _: EntityStreamSizeException =>
                FileTooLarge(maxFileSize, storageAvailableSpace)
              case th                           =>
                WrappedAkkaRejection(MalformedRequestContentRejection(th.getMessage, th))
            }.flatMap(IO.fromOption(_, InvalidMultipartFieldName(id)))
          }
      }

      private def detectContentType(filename: String, contentTypeFromAkka: ContentType) = {
        val bodyDefinedContentType = Option.when(contentTypeFromAkka != defaultContentType)(contentTypeFromAkka)

        def detectFromConfig = for {
          extension       <- FileUtils.extension(filename)
          customMediaType <- mediaTypeDetector.find(extension)
        } yield ContentType(customMediaType, () => HttpCharsets.`UTF-8`)

        bodyDefinedContentType.orElse(detectFromConfig).getOrElse(contentTypeFromAkka)
      }
    }
}

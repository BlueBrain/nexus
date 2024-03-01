package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`multipart/form-data`
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, MultipartUnmarshallers, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.NotARejection
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCustomMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName, WrappedAkkaRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json
import io.circe.parser.parse

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
    *   the Miltipart/FormData payload
    * @param maxFileSize
    *   the file size limit to be uploaded, provided by the storage
    * @param storageAvailableSpace
    *   the remaining available space on the storage
    * @return
    *   the file metadata. plus the entity with the file content
    */
  def apply(
      id: Iri,
      entity: HttpEntity,
      maxFileSize: Long,
      storageAvailableSpace: Option[Long]
  ): IO[UploadedInformation]
}

sealed trait UploadedInformation

case object NoInformation extends UploadedInformation

case class UploadedMetadata(
    keywords: Map[Label, String],
    description: Option[String],
    name: Option[String]
) extends UploadedInformation

case class UploadedFileInformation(
    filename: String,
    keywords: Map[Label, String],
    description: Option[String],
    name: Option[String],
    suppliedContentType: ContentType,
    contents: BodyPartEntity
) extends UploadedInformation

object FormDataExtractor {

  private val FileFieldName: String     = "file"
  private val MetadataFieldName: String = "metadata"

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
          maxFileSize: Long,
          storageAvailableSpace: Option[Long]
      ): IO[UploadedInformation] = {
        val sizeLimit = Math.min(storageAvailableSpace.getOrElse(Long.MaxValue), maxFileSize)
        for {
          formData <- unmarshall(entity, sizeLimit)
          fileOpt  <- extractFile(formData, maxFileSize, storageAvailableSpace)
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
          maxFileSize: Long,
          storageAvailableSpace: Option[Long]
      ): IO[Option[UploadedInformation]] = IO
        .fromFuture(
          IO(
            formData.parts
              .foldAsync(NoInformation: UploadedInformation) { case (x, part) =>
                extractFile(part, x)
              }
              .toMat(Sink.headOption)(Keep.right)
              .run()
          )
        )
        .adaptError {
          case _: EntityStreamSizeException =>
            FileTooLarge(maxFileSize, storageAvailableSpace)
          case NotARejection(th)            =>
            WrappedAkkaRejection(MalformedRequestContentRejection(th.getMessage, th))
        }

      private def extractFile(
          part: FormData.BodyPart,
          existingInfo: UploadedInformation
      ): Future[UploadedInformation] = part match {
        case part if part.name == MetadataFieldName =>
          asJson(part.entity.dataBytes)
            .map(x => x.as[FileCustomMetadata].toOption)
            .map {
              case Some(md) =>
                UploadedMetadata(
                  md.keywords.getOrElse(Map.empty),
                  md.description,
                  md.name
                )
              case None     => NoInformation
            }
        case part if part.name == FileFieldName     =>
          val filename    = part.filename.getOrElse("file")
          val contentType = detectContentType(filename, part.entity.contentType)

          val result = existingInfo match {
            case UploadedMetadata(keywords, description, name) =>
              UploadedFileInformation(
                filename,
                keywords,
                description,
                name,
                contentType,
                part.entity
              )
            case _                                             =>
              UploadedFileInformation(
                filename,
                Map.empty,
                None,
                None,
                contentType,
                part.entity
              )
          }
          Future.successful(result)
        case _                                      => Future.successful(NoInformation)
      }

      private def consume(source: Source[ByteString, Any])(implicit materializer: Materializer): Future[String] =
        source.runFold("")(_ ++ _.utf8String)

      private def consume(source: Source[ByteString, Any], entries: Long)(implicit
          materializer: Materializer
      ): Future[String] =
        source.take(entries).runFold("")(_ ++ _.utf8String)

      def asString(source: Source[ByteString, Any], entries: Option[Long])(implicit
          materializer: Materializer
      ): Future[String] =
        entries.fold(consume(source))(consume(source, _))

      def asJson(source: Source[ByteString, Any], entries: Option[Long] = None)(implicit
          materializer: Materializer
      ): Future[Json] = {
        val consumed = asString(source, entries)
        consumed.map { s =>
          parse(s) match {
            case Left(err)    =>
              throw new Exception(s"Error converting '$consumed' to Json. Details: '${err.getMessage()}'")
            case Right(value) => value
          }
        }
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

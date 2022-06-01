package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FileEventExchange.{AttributesUpdated, FileExtraFields}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileTagAdded, FileTagDeleted, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.{EventExchangeResult, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, JsonValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

import scala.annotation.nowarn

/**
  * File specific [[EventExchange]] implementation.
  *
  * @param files
  *   the files module
  */
class FileEventExchange(files: Files)(implicit base: BaseUri, config: StorageTypeConfig) extends EventExchange {

  override type A = File
  override type E = FileEvent
  override type M = File

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[E]] =
    event match {
      case ev: FileEvent => Some(JsonValue(ev))
      case _             => None
    }

  def toMetric(event: Event): UIO[Option[EventMetric]] = {
    def fetchFile: (Iri, ProjectRef) => UIO[FileResource] = (id: Iri, project: ProjectRef) =>
      files.fetch(id, project).hideErrorsWith(e => new IllegalStateException(e.reason))
    event match {
      case f: FileEvent =>
        {
          f match {
            case c: FileCreated if c.attributes.digest.computed =>
              UIO.pure(
                EventMetric.Created -> FileExtraFields(
                  c.storage.iri,
                  Some(c.attributes.bytes),
                  c.attributes.mediaType,
                  Some(c.attributes.origin)
                )
              )
            case c: FileCreated                                 =>
              UIO.pure(EventMetric.Created -> FileExtraFields(c.storage.iri, None, None, Some(c.attributes.origin)))
            case u: FileUpdated if u.attributes.digest.computed =>
              UIO.pure(
                EventMetric.Updated -> FileExtraFields(
                  u.storage.iri,
                  Some(u.attributes.bytes),
                  u.attributes.mediaType,
                  Some(u.attributes.origin)
                )
              )
            case u: FileUpdated                                 =>
              UIO.pure(
                EventMetric.Updated -> FileExtraFields(
                  u.storage.iri,
                  None,
                  None,
                  Some(u.attributes.origin)
                )
              )
            case fau: FileAttributesUpdated                     =>
              fetchFile(fau.id, fau.project).map { f =>
                AttributesUpdated -> FileExtraFields(
                  f.value.storage.iri,
                  Some(fau.bytes),
                  fau.mediaType,
                  Some(FileAttributesOrigin.Storage)
                )
              }
            case fta: FileTagAdded                              =>
              fetchFile(fta.id, fta.project).map { f =>
                EventMetric.Tagged -> FileExtraFields(f.value.storage.iri, None, None, None)
              }
            case fta: FileTagDeleted                            =>
              fetchFile(fta.id, fta.project).map { f =>
                EventMetric.TagDeleted -> FileExtraFields(f.value.storage.iri, None, None, None)
              }
            case fd: FileDeprecated                             =>
              fetchFile(fd.id, fd.project).map { f =>
                EventMetric.Deprecated -> FileExtraFields(f.value.storage.iri, None, None, None)
              }
          }
        }.map { case (action, extraFields) =>
          Some(
            ProjectScopedMetric.from[FileEvent](
              f,
              action,
              f.id,
              Set(nxvFile),
              extraFields.asJsonObject
            )
          )
        }
      case _            => UIO.none
    }
  }

  override def toResource(event: Event, tag: Option[UserTag]): UIO[Option[EventExchangeResult]] =
    event match {
      case ev: FileEvent => resourceToValue(files.fetch(IdSegmentRef.fromTagOpt(ev.id, tag), ev.project), ev.id)
      case _             => UIO.none
    }

  private def resourceToValue(resourceIO: IO[FileRejection, FileResource], id: Iri)(implicit enc: JsonLdEncoder[A]) =
    resourceIO.map(Files.eventExchangeValue).redeem(_ => Some(TagNotFound(id)), Some(_))
}

object FileEventExchange {

  /**
    * Specific action for files
    */
  val AttributesUpdated: Label = Label.unsafe("AttributesUpdated")

  final private case class FileExtraFields(
      storage: Iri,
      bytes: Option[Long],
      mediaType: Option[ContentType],
      origin: Option[FileAttributesOrigin]
  )

  private object FileExtraFields {
    @nowarn("cat=unused")
    implicit private val config: Configuration                             = Configuration.default
    implicit val fileExtraFieldsEncoder: Encoder.AsObject[FileExtraFields] = deriveConfiguredEncoder[FileExtraFields]
  }

}

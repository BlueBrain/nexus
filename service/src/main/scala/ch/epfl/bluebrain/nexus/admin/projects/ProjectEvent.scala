package ch.epfl.bluebrain.nexus.admin.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.admin.config.Contexts._
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.collection.mutable.ListBuffer

sealed trait ProjectEvent extends Product with Serializable {

  /**
    * @return the permanent identifier for the project
    */
  def id: UUID

  /**
    * @return the revision number that this event generates
    */
  def rev: Long

  /**
    * @return the timestamp associated to this event
    */
  def instant: Instant

  /**
    * @return the identity associated to this event
    */
  def subject: Subject
}

object ProjectEvent {

  /**
    * Evidence that a project has been created.
    *
    * @param id                the permanent identifier for the project
    * @param label             the label (segment) of the project
    * @param organizationUuid  the permanent identifier for the parent organization
    * @param organizationLabel the parent organization label
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectCreated(
      id: UUID,
      label: String,
      organizationUuid: UUID,
      organizationLabel: String,
      description: Option[String],
      apiMappings: Map[String, AbsoluteIri],
      base: AbsoluteIri,
      vocab: AbsoluteIri,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent {

    /**
      *  the revision number that this event generates
      */
    val rev: Long = 1L
  }

  /**
    * Evidence that a project has been updated.
    *
    * @param id          the permanent identifier for the project
    * @param label       the label (segment) of the project
    * @param description an optional project description
    * @param apiMappings the API mappings
    * @param base        the base IRI for generated resource IDs
    * @param vocab       an optional vocabulary for resources with no context
    * @param rev         the revision number that this event generates
    * @param instant     the timestamp associated to this event
    * @param subject     the identity associated to this event
    */
  final case class ProjectUpdated(
      id: UUID,
      label: String,
      description: Option[String],
      apiMappings: Map[String, AbsoluteIri],
      base: AbsoluteIri,
      vocab: AbsoluteIri,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been deprecated.
    *
    * @param id      the permanent identifier for the project
    * @param rev     the revision number that this event generates
    * @param instant the timestamp associated to this event
    * @param subject the identity associated to this event
    */
  final case class ProjectDeprecated(
      id: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  object JsonLd {
    @silent // implicits are not recognized as being used
    final implicit def projectEventEncoder(implicit ic: IamClientConfig): Encoder[ProjectEvent] = {
      implicit val config: Configuration = Configuration.default
        .withDiscriminator("@type")
        .copy(transformMemberNames = {
          case nxv.`@id`.name             => nxv.uuid.prefix
          case nxv.label.name             => nxv.label.prefix
          case nxv.organizationUuid.name  => nxv.organizationUuid.prefix
          case nxv.organizationLabel.name => nxv.organizationLabel.prefix
          case nxv.rev.name               => nxv.rev.prefix
          case nxv.instant.name           => nxv.instant.prefix
          case nxv.subject.name           => nxv.subject.prefix
          case other                      => other
        })
      implicit val subjectIdEncoder: Encoder[Subject] = Encoder.encodeJson.contramap(_.id.asJson)
      implicit val apiMappingEncoder: Encoder[Map[String, AbsoluteIri]] =
        Encoder.encodeJson.contramap { map =>
          Json.arr(
            map
              .foldLeft(ListBuffer.newBuilder[Json]) {
                case (acc, (prefix, namespace)) =>
                  acc += Json.obj(nxv.prefix.name -> Json.fromString(prefix), nxv.namespace.name -> namespace.asJson)
              }
              .result()
              .toSeq: _*
          )
        }
      Encoder.encodeJson.contramap[ProjectEvent] { ev =>
        deriveConfiguredEncoder[ProjectEvent]
          .mapJson { json =>
            val rev = Json.obj(nxv.rev.prefix -> Json.fromLong(ev.rev))
            json
              .deepMerge(rev)
              .addContext(adminCtxUri)
              .addContext(resourceCtxUri)
          }
          .apply(ev)
      }
    }
  }

}

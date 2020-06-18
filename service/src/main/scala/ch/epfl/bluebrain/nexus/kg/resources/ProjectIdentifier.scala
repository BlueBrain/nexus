package ch.epfl.bluebrain.nexus.kg.resources

import java.util.UUID

import cats.data.{EitherT, OptionT}
import cats.implicits._
import cats.{Applicative, Show}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, Caller, Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{InvalidIdentity, ProjectLabelNotFound, ProjectRefNotFound}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.{GraphDecoder, GraphEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import scala.util.Try

/**
  * Enumeration of project identifier types
  */
sealed trait ProjectIdentifier extends Product with Serializable {

  /**
    * Attempts to convert the current ProjectIdentifier to a [[ProjectLabel]] using the passed cache
    *
    * @tparam F the effect type
    */
  def toLabel[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectLabel]

  /**
    * Attempts to convert the current ProjectIdentifier to a [[ProjectRef]] using the passed cache
    *
    * @tparam F the effect type
    */
  def toRef[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef]

  /**
    * Attempts to convert the current ProjectIdentifier to a [[ProjectRef]] using the passed cache.
    * Before the conversion is applied, the passed ''identities'' must have the passed ''perm'' inside the ''acls'' path.
    *
    * @tparam F the effect type
    */
  def toRef[F[_]: Applicative](
      perm: Permission,
      identities: Set[Identity]
  )(implicit acls: AccessControlLists, caller: Caller, cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef]

  /**
    * Finds in the passed ''projects'' set the current [[ProjectIdentifier]]
    *
    * @param projects a set of projects
    * @return Some(project) if found, None otherwise
    */
  def findIn(projects: Set[Project]): Option[Project]

}

object ProjectIdentifier {

  /**
    * Representation of the project label, containing both the organization and the project segments
    *
    * @param organization the organization segment of the label
    * @param value        the project segment of the label
    */
  final case class ProjectLabel(organization: String, value: String) extends ProjectIdentifier {
    lazy val notFound: Rejection                                                                         = ProjectRefNotFound(this)
    def toLabel[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectLabel] =
      EitherT.rightT(this)

    def toRef[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef] =
      OptionT(cache.get(this)).map(_.ref).toRight(notFound)

    def toRef[F[_]: Applicative](
        perm: Permission,
        identities: Set[Identity]
    )(implicit acls: AccessControlLists, caller: Caller, cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef] =
      if (!identities.toSeq.foundInCaller) EitherT.leftT(InvalidIdentity(): Rejection)
      else if (!acls.exists(identities, this, perm)) EitherT.leftT(notFound)
      else toRef

    def findIn(projects: Set[Project]): Option[Project] = projects.find(_.projectLabel == this)

  }

  object ProjectLabel {
    implicit val segmentShow: Show[ProjectLabel]            = Show.show(s => s"${s.organization}/${s.value}")
    implicit val projectLabelEncoder: Encoder[ProjectLabel] =
      Encoder.encodeString.contramap(_.show)

    implicit val projectLabelDecoder: Decoder[ProjectLabel] = Decoder.decodeString.emap { s =>
      s.split("/", 2) match {
        case Array(org, project) if !project.contains("/") => Right(ProjectLabel(org, project))
        case _                                             => Left(s"'$s' cannot be converted to ProjectLabel")
      }
    }

    implicit final val projectLabelGraphDecoder: GraphDecoder[ProjectLabel] =
      GraphDecoder.graphDecodeString.emap { str =>
        str.trim.split("/") match {
          case Array(organization, project) => Right(ProjectLabel(organization, project))
          case _                            => Left(s"Unable to decode ProjectLabel from string '$str'")
        }
      }

    implicit final val projectLabelGraphEncoder: GraphEncoder[ProjectLabel] =
      GraphEncoder.graphEncodeString.contramap(_.show)
  }

  /**
    * A stable project reference.
    *
    * @param id the underlying stable identifier for a project
    */
  final case class ProjectRef(id: UUID) extends ProjectIdentifier {
    private lazy val notFound: Rejection = ProjectLabelNotFound(this)

    def toLabel[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectLabel] =
      OptionT(cache.getLabel(this)).toRight(notFound)

    def toRef[F[_]: Applicative](implicit cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef] =
      EitherT.rightT(this)

    def toRef[F[_]: Applicative](
        perm: Permission,
        identities: Set[Identity]
    )(implicit acls: AccessControlLists, caller: Caller, cache: ProjectCache[F]): EitherT[F, Rejection, ProjectRef] =
      toRef

    def findIn(projects: Set[Project]): Option[Project] = projects.find(_.uuid == id)
  }

  object ProjectRef {

    implicit val projectRefShow: Show[ProjectRef]       = Show.show(_.id.toString)
    implicit val projectRefEncoder: Encoder[ProjectRef] =
      Encoder.encodeString.contramap(_.show)

    implicit val projectRefDecoder: Decoder[ProjectRef] =
      Decoder.decodeString.emapTry(uuid => Try(UUID.fromString(uuid)).map(ProjectRef.apply))

    implicit final val projectRefGraphDecoder: GraphDecoder[ProjectRef] =
      GraphDecoder.graphDecodeUUID.map(uuid => ProjectRef(uuid))

    implicit final val projectRefGraphEncoder: GraphEncoder[ProjectRef] =
      GraphEncoder.graphEncodeUUID.contramap(_.id)
  }

  implicit val projectIdentifierEncoder: Encoder[ProjectIdentifier] =
    Encoder.encodeJson.contramap {
      case value: ProjectLabel => value.asJson
      case value: ProjectRef   => value.asJson
    }

  implicit val projectIdentifierDecoder: Decoder[ProjectIdentifier] =
    Decoder.instance { h =>
      ProjectRef.projectRefDecoder(h) match {
        case Left(_) => ProjectLabel.projectLabelDecoder(h)
        case other   => other
      }
    }

  implicit final val projectIdentifierGraphDecoder: GraphDecoder[ProjectIdentifier] =
    ProjectLabel.projectLabelGraphDecoder.asInstanceOf[GraphDecoder[ProjectIdentifier]] or
      ProjectRef.projectRefGraphDecoder.asInstanceOf[GraphDecoder[ProjectIdentifier]]

  implicit final val projectIdentifierGraphEncoder: GraphEncoder[ProjectIdentifier] = GraphEncoder.instance {
    case label: ProjectLabel => label.asGraph
    case ref: ProjectRef     => ref.asGraph
  }

  implicit val projectIdentifierShow: Show[ProjectIdentifier] =
    Show.show {
      case value: ProjectLabel => value.show
      case value: ProjectRef   => value.show
    }
}

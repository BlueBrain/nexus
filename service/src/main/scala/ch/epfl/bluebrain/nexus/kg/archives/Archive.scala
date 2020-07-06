package ch.epfl.bluebrain.nexus.kg.archives

import java.time.{Clock, Instant}

import cats.Monad
import cats.data.{EitherT, OptionT}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.types.{Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.archives.Archive.ResourceDescription
import ch.epfl.bluebrain.nexus.service.config.AppConfig.ArchivesConfig
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Id, Rejection, ResId}
import ch.epfl.bluebrain.nexus.kg.storage.AkkaSource
import ch.epfl.bluebrain.nexus.rdf.Iri.Path.{Segment, Slash}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.{Cursor, Graph, GraphDecoder}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.{nxv, nxva}

import scala.annotation.tailrec

/**
  * Describes a set of resources
  */
final case class Archive(resId: ResId, created: Instant, createdBy: Identity, values: Set[ResourceDescription]) {

  def toTarIgnoreNotFound[F[_]: Effect](implicit fetchResource: FetchResource[F, ArchiveSource]): F[AkkaSource] =
    values.toList.traverse(fetchResource(_).value).map(results => TarFlow.write(results.flatten))

  def toTar[F[_]: Effect](implicit fetchResource: FetchResource[F, ArchiveSource]): OptionT[F, AkkaSource] =
    values.toList.traverse(fetchResource(_)).map(TarFlow.write(_))
}

object Archive {

  val write: Permission = Permission.unsafe("archives/write")

  /**
    * Enumeration of resource descriptions
    */
  sealed trait ResourceDescription extends Product with Serializable {
    def path: Option[Path]
  }

  /**
    * Description of a file resource
    *
    * @param id             the unique identifier of the resource in a given project
    * @param project        the project where the resource belongs
    * @param rev            the optional revision of the resource
    * @param tag            the optional tag of the resource
    * @param path           the optional relative path on the tar bundle for the targeted resource
    */
  final case class File(
      id: AbsoluteIri,
      project: ProjectResource,
      rev: Option[Long],
      tag: Option[String],
      path: Option[Path]
  ) extends ResourceDescription

  /**
    * Description of a non file resource
    *
    * @param id             the unique identifier of the resource in a given project
    * @param project        the project where the resource belongs
    * @param rev            the optional revision of the resource
    * @param tag            the optional tag of the resource
    * @param originalSource a flag to decide whether the original payload or the payload with metadata and JSON-LD context are going to be fetched. Default = true
    * @param path           the optional relative path on the tar bundle for the targeted resource
    */
  final case class Resource(
      id: AbsoluteIri,
      project: ProjectResource,
      rev: Option[Long],
      tag: Option[String],
      originalSource: Boolean,
      path: Option[Path]
  ) extends ResourceDescription

  private type ProjectResolver[F[_]] = Option[ProjectLabel] => EitherT[F, Rejection, ProjectResource]

  implicit final val pathDecoder: GraphDecoder[Path] =
    GraphDecoder.graphDecodeString.emap { str =>
      Path.rootless(str) match {
        case Right(path) if path.nonEmpty && path != Path./ && !path.endsWithSlash => Right(path)
        case _                                                                     => Left(s"Unable to decode string '$str' as a Path.")
      }
    }

  private def resourceDescriptions[F[_]: Monad](mainId: AbsoluteIri, cursor: Cursor)(implicit
      projectResolver: ProjectResolver[F]
  ): EitherT[F, Rejection, Set[ResourceDescription]] = {

    def resourceDescription(c: Cursor): EitherT[F, Rejection, ResourceDescription] = {
      val result = for {
        id             <- c.down(nxv.resourceId).as[AbsoluteIri].onError(mainId.ref, "resourceId")
        tpe            <- c.down(rdf.tpe).as[AbsoluteIri].onError(id.ref, "@type")
        rev            <- c.down(nxva.rev).as[Option[Long]].onError(id.ref, "rev")
        tag            <- c.down(nxva.tag).as[Option[String]].onError(id.ref, "tag")
        projectLabel   <- c.down(nxva.project).as[Option[ProjectLabel]].onError(id.ref, "project")
        originalSource <- c.down(nxv.originalSource)
                            .as[Option[Boolean]]
                            .map(_.getOrElse(true))
                            .onError(id.ref, "originalSource")
        path           <- c.down(nxv.path).as[Option[Path]].onError(id.ref, "path")
      } yield (id, tpe, rev, tag, projectLabel, originalSource, path)

      EitherT.fromEither[F](result).flatMap {
        case (id, _, Some(_), Some(_), _, _, _)                                                   =>
          val rej = InvalidResourceFormat(id.ref, "'tag' and 'rev' cannot be present at the same time.")
          EitherT.leftT[F, ResourceDescription](rej)
        case (id, tpe, rev, tag, projectLabel, _, path) if tpe == nxv.File.value                  =>
          projectResolver(projectLabel).map(project => File(id, project, rev, tag, path))
        case (id, tpe, rev, tag, projectLabel, originalSource, path) if tpe == nxv.Resource.value =>
          projectResolver(projectLabel).map(project => Resource(id, project, rev, tag, originalSource, path))
        case (id, tpe, _, _, _, _, _)                                                             =>
          val msg =
            s"Invalid '@type' field '$tpe'. Recognized types are '${nxv.File.value}' and '${nxv.Resource.value}'."
          EitherT.leftT[F, ResourceDescription](InvalidResourceFormat(id.ref, msg))
      }
    }
    cursor.cursors.getOrElse(Set.empty).toList.foldM(Set.empty[ResourceDescription]) { (acc, c) =>
      resourceDescription(c).map(acc + _)
    }
  }

  /**
    * Attempts to constructs a [[Archive]] from the provided graph
    *
    * @param id      the resource identifier
    * @param graph   the graph
    * @param cache   the project cache from where to obtain the project references
    * @param project the project where the resource bundle is created
    * @return Right(archive) when successful and Left(rejection) when failed
    */
  final def apply[F[_]](id: AbsoluteIri, graph: Graph)(implicit
      cache: ProjectCache[F],
      project: ProjectResource,
      F: Monad[F],
      config: ArchivesConfig,
      subject: Subject,
      clock: Clock
  ): EitherT[F, Rejection, Archive] = {

    implicit val projectResolver: ProjectResolver[F] = {
      case Some(label: ProjectLabel) =>
        OptionT(cache.getBy(label)).toRight[Rejection](ProjectRefNotFound(label))
      case None                      =>
        EitherT.rightT[F, Rejection](project)
    }

    def duplicatedPathCheck(resources: Set[ResourceDescription]): EitherT[F, Rejection, Unit] = {
      val parents         = resources.foldLeft(Set.empty[Path]) {
        case (acc, c) if c.path.exists(acc.contains)    => acc
        case (acc, Resource(_, _, _, _, _, Some(path))) => acc ++ parentsOf(path)
        case (acc, File(_, _, _, _, Some(path)))        => acc ++ parentsOf(path)
        case (acc, _)                                   => acc
      }
      val (duplicated, _) = resources.foldLeft((false, parents)) {
        case ((true, acc), _)                                => (true, acc)
        case ((_, acc), Resource(_, _, _, _, _, Some(path))) => (acc.contains(path), acc + path)
        case ((_, acc), File(_, _, _, _, Some(path)))        => (acc.contains(path), acc + path)
        case ((_, acc), _)                                   => (false, acc)
      }
      if (duplicated) EitherT.leftT[F, Unit](InvalidResourceFormat(id.ref, "Duplicated 'path' fields"): Rejection)
      else EitherT.rightT[F, Rejection](())
    }

    def parentsOf(path: Path): Set[Path] = {
      @tailrec def inner(p: Path, acc: Set[Path] = Set.empty): Set[Path] =
        p match {
          case Segment(_, Slash(parent)) => inner(parent, acc + parent)
          case Segment(_, parent)        => inner(parent, acc + parent)
          case Slash(parent)             => inner(parent, acc + parent)
          case _                         => acc
        }
      inner(path).filterNot(p => p.isEmpty || p == Path./)
    }

    def maxResourcesCheck(resources: Set[ResourceDescription]): EitherT[F, Rejection, Unit] =
      if (resources.size > config.maxResources) {
        val msg = s"Too many resources. Maximum resources allowed: '${config.maxResources}'. Found: '${resources.size}'"
        EitherT.leftT[F, Unit](InvalidResourceFormat(id.ref, msg): Rejection)
      } else EitherT.rightT[F, Rejection](())

    for {
      resources <- resourceDescriptions[F](id, graph.cursor.downSet(nxv.resources))
      _         <- maxResourcesCheck(resources)
      _         <- duplicatedPathCheck(resources)
    } yield Archive(Id(ProjectRef(project.uuid), id), clock.instant, subject, resources)
  }
}

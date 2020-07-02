package ch.epfl.bluebrain.nexus.kg.archives

import cats.syntax.show._
import ch.epfl.bluebrain.nexus.kg.archives.Archive.ResourceDescription
import ch.epfl.bluebrain.nexus.rdf.Node._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphEncoder}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.{nxv, nxva}

/**
  * Encoders for [[Archive]]
  */
object ArchiveEncoder {

  private val resourceDescriptionGraphEncoder: GraphEncoder[ResourceDescription] = GraphEncoder {
    case Archive.File(id, project, rev, tag, path)                     =>
      Graph(blank)
        .append(rdf.tpe, nxv.File)
        .append(nxv.resourceId, id)
        .append(nxva.project, project.value.show)
        .append(nxva.rev, GraphEncoder[Option[Long]].apply(rev))
        .append(nxva.tag, GraphEncoder[Option[String]].apply(tag))
        .append(nxv.path, GraphEncoder[Option[String]].apply(path.map(_.pctEncoded)))
    case Archive.Resource(id, project, rev, tag, originalSource, path) =>
      Graph(blank)
        .append(rdf.tpe, nxv.Resource)
        .append(nxv.resourceId, id)
        .append(nxva.project, project.value.show)
        .append(nxv.originalSource, originalSource)
        .append(nxva.rev, GraphEncoder[Option[Long]].apply(rev))
        .append(nxva.tag, GraphEncoder[Option[String]].apply(tag))
        .append(nxv.path, GraphEncoder[Option[String]].apply(path.map(_.pctEncoded)))
  }

  implicit final val archiveGraphEncoder: GraphEncoder[Archive] = GraphEncoder { archive =>
    val typed = Graph(archive.resId.value).append(rdf.tpe, nxv.Archive)
    archive.values.foldLeft(typed) {
      case (acc, rd) => acc.append(nxv.resources, resourceDescriptionGraphEncoder(rd))
    }
  }
}

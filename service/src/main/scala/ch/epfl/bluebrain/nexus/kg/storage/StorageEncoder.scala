package ch.epfl.bluebrain.nexus.kg.storage

import ch.epfl.bluebrain.nexus.kg.storage.Storage._
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node._
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphEncoder}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv

/**
  * Encoders for [[Storage]]
  */
object StorageEncoder {

  implicit val storageGraphEncoderWithCredentials: GraphEncoder[Storage] =
    GraphEncoder {
      case storage: DiskStorage       =>
        val triples = mainTriples(storage) ++ Set[Triple](
          (storage.id, rdf.tpe, nxv.DiskStorage),
          (storage.id, nxv.volume, storage.volume.toString)
        )
        Graph(storage.id, triples)
      case storage: RemoteDiskStorage =>
        val triples = mainTriples(storage) ++ Set[Triple](
          (storage.id, rdf.tpe, nxv.RemoteDiskStorage),
          (storage.id, nxv.endpoint, storage.endpoint.toString()),
          (storage.id, nxv.folder, storage.folder)
        ) ++ storage.credentials.map(cred => ((storage.id, nxv.credentials, cred): Triple))
        Graph(storage.id, triples)
      case storage: S3Storage         =>
        val main        = mainTriples(storage)
        val triples     = Set[Triple]((storage.id, rdf.tpe, nxv.S3Storage), (storage.id, nxv.bucket, storage.bucket))
        val region      = storage.settings.region.map(region => (storage.id, nxv.region, region): Triple)
        val endpoint    = storage.settings.endpoint.map(endpoint => (storage.id, nxv.endpoint, endpoint): Triple)
        val credentials = storage.settings.credentials.map(cred => (storage.id, nxv.accessKey, cred.accessKey): Triple)
        Graph(storage.id, main ++ triples ++ region ++ endpoint ++ credentials)
    }

  private def mainTriples(storage: Storage): Set[Triple] = {
    val s = IriNode(storage.id)
    Set(
      (s, rdf.tpe, nxv.Storage),
      (s, nxv.deprecated, storage.deprecated),
      (s, nxv.maxFileSize, storage.maxFileSize),
      (s, nxv.rev, storage.rev),
      (s, nxv.default, storage.default),
      (s, nxv.algorithm, storage.algorithm),
      (s, nxv.readPermission, storage.readPermission.value),
      (s, nxv.writePermission, storage.writePermission.value)
    )
  }
}

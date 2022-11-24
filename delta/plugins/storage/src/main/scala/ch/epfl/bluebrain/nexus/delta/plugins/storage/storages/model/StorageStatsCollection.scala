package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.Semigroup
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Decoder, Encoder}

// TODO: Find a better name for this object
object StorageStatsCollection {

  type StoragesStats = Map[Iri, StorageStatEntry]

  /**
    * The stats for a single storage
    *
    * @param files
    *   the number of physical files for this storage
    * @param spaceUsed
    *   the space used by the files for this storage
    */
  final case class StorageStatEntry(files: Long, spaceUsed: Long)

  object StorageStatEntry {

    val empty: StorageStatEntry = StorageStatEntry(0L, 0L)

    implicit val storageStatEntrySemigroup: Semigroup[StorageStatEntry] =
      (x: StorageStatEntry, y: StorageStatEntry) =>
        StorageStatEntry(
          x.files + y.files,
          x.spaceUsed + y.spaceUsed
        )

    implicit val storageStatEntryEncoder: Encoder[StorageStatEntry] =
      deriveCodec[StorageStatEntry]

    implicit val storageStatEntryJsonLdEncoder: JsonLdEncoder[StorageStatEntry] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.storages))

    val singleStorageStatResultDecoder: Decoder[StorageStatEntry] =
      Decoder.instance { hc =>
        for {
          size  <- hc.downField("aggregations").downField("storageSize").get[Long]("value")
          files <- hc.downField("aggregations").downField("filesCount").get[Long]("value")
        } yield StorageStatEntry(files, size)
      }

  }

  implicit val storagesStatsDecoder: Decoder[StoragesStats] = {
    case class StorageBucket(key: Iri, size: Long, files: Long)
    implicit val storageBucketDecoder: Decoder[StorageBucket] =
      Decoder.instance { hc =>
        for {
          key   <- hc.get[Iri]("key")
          size  <- hc.downField("storageSize").get[Long]("value")
          files <- hc.downField("filesCount").get[Long]("value")
        } yield StorageBucket(key, size, files)
      }

    Decoder.instance { hc =>
      for {
        projects <- hc.downField("aggregations")
                      .downField("by_storage")
                      .get[Vector[StorageBucket]]("buckets")
      } yield projects.map(v => v.key -> StorageStatEntry(v.files, v.size)).toMap
    }
  }

}

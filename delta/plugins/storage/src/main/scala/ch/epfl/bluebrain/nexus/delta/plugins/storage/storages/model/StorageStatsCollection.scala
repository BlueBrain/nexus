package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.Semigroup
import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}

import java.time.Instant
import scala.math.Ordering.Implicits._

final case class StorageStatsCollection(value: Map[ProjectRef, Map[Iri, StorageStatEntry]]) {

  /**
    * Remove statistics for the given project
    */
  def -(projectRef: ProjectRef): StorageStatsCollection = copy(value = value - projectRef)

  /**
    * Attempts to fetch the counts for a storage
    * @param project
    *   the project reference
    * @param storageId
    *   the storage identifier
    */
  def get(project: ProjectRef, storageId: Iri): Option[StorageStatEntry] = value.get(project).flatMap(_.get(storageId))

  /**
    * Update statistics for the given storage by merging the existing one with the provided stat entry
    * @param project
    *   the project reference
    * @param storageId
    *   the storage identifier
    * @param statEntry
    *   the entry to merge to the existing one
    */
  def update(project: ProjectRef, storageId: Iri, statEntry: StorageStatEntry): StorageStatsCollection =
    copy(value = value |+| Map(project -> Map(storageId -> statEntry)))

}

object StorageStatsCollection {

  /**
    * An empty [[StorageStatsCollection]]
    */
  val empty: StorageStatsCollection = StorageStatsCollection(Map.empty)

  /**
    * The stats for a single storage
    *
    * @param files
    *   the number of physical files for this storage
    * @param spaceUsed
    *   the space used by the files for this storage
    * @param lastProcessedEventDateTime
    *   the time when the last entry was created
    */
  final case class StorageStatEntry(files: Long, spaceUsed: Long, lastProcessedEventDateTime: Option[Instant])

  object StorageStatEntry {

    val empty: StorageStatEntry = StorageStatEntry(0L, 0L, None)

    implicit val storageStatEntrySemigroup: Semigroup[StorageStatEntry] =
      (x: StorageStatEntry, y: StorageStatEntry) =>
        StorageStatEntry(
          x.files + y.files,
          x.spaceUsed + y.spaceUsed,
          x.lastProcessedEventDateTime.max(y.lastProcessedEventDateTime)
        )

    implicit val storageStatEntryCodec: Codec[StorageStatEntry] = deriveCodec[StorageStatEntry]

    implicit val storageStatEntryJsonLdEncoder: JsonLdEncoder[StorageStatEntry] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.storages))

  }

  implicit val storageStatsCollectionMonoid: Monoid[StorageStatsCollection] =
    new Monoid[StorageStatsCollection] {
      override def empty: StorageStatsCollection = StorageStatsCollection.empty

      override def combine(x: StorageStatsCollection, y: StorageStatsCollection): StorageStatsCollection =
        StorageStatsCollection(x.value |+| y.value)
    }

  implicit val storageStatsCollectionEncoder: Encoder[StorageStatsCollection] =
    Encoder.encodeMap[ProjectRef, Map[Iri, StorageStatEntry]].contramap(_.value)

  implicit val storageStatsCollectionDecoder: Decoder[StorageStatsCollection] =
    Decoder.decodeMap[ProjectRef, Map[Iri, StorageStatEntry]].map(StorageStatsCollection(_))

}

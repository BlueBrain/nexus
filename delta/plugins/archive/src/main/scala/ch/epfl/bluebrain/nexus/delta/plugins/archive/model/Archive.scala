package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet

/**
  * An archive value with its ttl.
  *
  * @param resources        the collection of resource references
  * @param expiresInSeconds the archive ttl
  */
final case class Archive(resources: NonEmptySet[ArchiveReference], expiresInSeconds: Long)

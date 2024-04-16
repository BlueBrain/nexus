package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF

import java.util.UUID

object UUIDFFixtures {
  trait Random {
    val randomUuid                       = UUID.randomUUID()
    implicit val fixedRandomUuidF: UUIDF = UUIDF.fixed(randomUuid)
  }

  trait Fixed {
    val fixedUuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    implicit val fixedUuidF: UUIDF = UUIDF.fixed(fixedUuid)
  }
}

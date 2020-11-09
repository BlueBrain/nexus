package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

final case class ServiceAccountConfig(subject: Subject)

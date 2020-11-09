package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

/**
 * Configuration for the application service account
 * @param subject the subject to be used for internal operations
 */
final case class ServiceAccountConfig(subject: Subject)

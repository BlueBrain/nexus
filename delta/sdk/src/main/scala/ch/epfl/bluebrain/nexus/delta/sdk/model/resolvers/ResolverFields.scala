package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import io.circe.Json

/**
  * Fields for a resolver
  * @param source            the original source of the resolver
  * @param value             additional fields to configure the resolver
  */
final case class ResolverFields(source: Json, value: ResolverValue)

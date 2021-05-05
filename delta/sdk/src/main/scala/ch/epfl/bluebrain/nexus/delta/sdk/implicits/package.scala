package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, KamonSyntax, TaskSyntax}
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetJsonLdInstances
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{HttpRequestSyntax, HttpResponseFieldsSyntax, IOSyntax}
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax.{OffsetSyntax, ProjectionStreamSyntax}

/**
  * Aggregate instances and syntax from rdf plus the current sdk instances and syntax to avoid importing multiple instances and syntax
  */
package object implicits
    extends TripleInstances
    with UriInstances
    with SecretInstances
    with OffsetJsonLdInstances
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with PathSyntax
    with IterableSyntax
    with KamonSyntax
    with IOSyntax
    with HttpRequestSyntax
    with HttpResponseFieldsSyntax
    with TaskSyntax
    with ClassTagSyntax
    with OffsetSyntax
    with ProjectionStreamSyntax

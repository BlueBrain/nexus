package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, KamonSyntax, TaskSyntax}
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{HttpRequestSyntax, HttpResponseFieldsSyntax, IOSyntax, ViewLensSyntax}

/**
  * Aggregate instances and syntax from rdf plus the current sdk instances and syntax to avoid importing multiple instances and syntax
  */
package object implicits
    extends TripleInstances
    with UriInstances
    with SecretInstances
    with OffsetInstances
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
    with ViewLensSyntax
    with ClassTagSyntax

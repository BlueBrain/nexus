package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, InstantSyntax, KamonSyntax, TaskSyntax}
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.{CredentialsInstances, IdentityInstances, IriInstances, ProjectRefInstances}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{HttpRequestSyntax, HttpResponseFieldsSyntax, IOSyntax, IriEncodingSyntax}

/**
  * Aggregate instances and syntax from rdf plus the current sdk instances and syntax to avoid importing multiple
  * instances and syntax
  */
package object implicits
    extends TripleInstances
    with UriInstances
    with SecretInstances
    with CredentialsInstances
    with IdentityInstances
    with IriInstances
    with ProjectRefInstances
    with JsonSyntax
    with IriSyntax
    with IriEncodingSyntax
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
    with InstantSyntax

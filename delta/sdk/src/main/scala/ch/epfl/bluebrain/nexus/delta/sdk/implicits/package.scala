package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.instances.ContentTypeInstances
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, Http4sResponseSyntax, IOSyntax, InstantSyntax, KamonSyntax}
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.{CredentialsInstances, HttpResponseFieldsInstances, IdentityInstances, IriInstances, ProjectRefInstances}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{HttpRequestSyntax, HttpResponseFieldsSyntax, IORejectSyntax, IriEncodingSyntax, ProjectionErrorsSyntax}
import org.http4s.circe.CirceInstances

/**
  * Aggregate instances and syntax from rdf plus the current sdk instances and syntax to avoid importing multiple
  * instances and syntax
  */
package object implicits
    extends TripleInstances
    with UriInstances
    with SecretInstances
    with CirceInstances
    with CredentialsInstances
    with Http4sResponseSyntax
    with HttpResponseFieldsInstances
    with IdentityInstances
    with IriInstances
    with ProjectRefInstances
    with ContentTypeInstances
    with JsonSyntax
    with IriSyntax
    with IriEncodingSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with PathSyntax
    with IterableSyntax
    with KamonSyntax
    with IORejectSyntax
    with HttpRequestSyntax
    with HttpResponseFieldsSyntax
    with IOSyntax
    with ClassTagSyntax
    with InstantSyntax
    with ProjectionErrorsSyntax

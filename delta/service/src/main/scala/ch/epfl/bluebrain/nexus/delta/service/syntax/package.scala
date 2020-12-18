package ch.epfl.bluebrain.nexus.delta.service

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, UriSyntax}

package object syntax
    extends EventLogSyntax
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with IterableSyntax
    with KamonSyntax

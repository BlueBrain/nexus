package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.Http4sResponseSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{TripleInstances, UriInstances}

package object instances
    extends CredentialsInstances
    with Http4sResponseSyntax
    with HttpResponseFieldsInstances
    with IdentityInstances
    with IriInstances
    with ProjectRefInstances
    with TripleInstances
    with UriInstances

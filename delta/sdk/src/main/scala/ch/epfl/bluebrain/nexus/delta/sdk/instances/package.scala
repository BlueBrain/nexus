package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.instances.ContentTypeInstances
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}

package object instances
    extends CredentialsInstances
    with IdentityInstances
    with IriInstances
    with ProjectRefInstances
    with TripleInstances
    with UriInstances
    with SecretInstances
    with MediaTypeInstances
    with ContentTypeInstances

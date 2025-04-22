package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.instances.ContentTypeInstances
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{SecretInstances, TripleInstances, UriInstances}
import org.http4s.circe.CirceInstances

package object instances
    extends CredentialsInstances
    with CirceInstances
    with HttpResponseFieldsInstances
    with IdentityInstances
    with IriInstances
    with ProjectRefInstances
    with TripleInstances
    with UriInstances
    with SecretInstances
    with MediaTypeInstances
    with ContentTypeInstances

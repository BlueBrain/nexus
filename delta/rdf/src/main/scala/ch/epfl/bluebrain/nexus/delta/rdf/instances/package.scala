package ch.epfl.bluebrain.nexus.delta.rdf

import org.http4s.circe.CirceInstances

package object instances extends TripleInstances with UriInstances with SecretInstances with CirceInstances

package ch.epfl.bluebrain.nexus.delta.sdk
import ch.epfl.bluebrain.nexus.delta.rdf.instances.TripleInstances

/**
  * Aggregate instances from rdf plus the current sdk instances to avoid importing multiple instances
  */
package object instances extends TripleInstances with UriInstances

package ch.epfl.bluebrain.nexus.kg.resources

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.kg.IdOffset

final case class CompositeViewOffset(values: Set[IdOffset]) extends Offset

package weather

import smithy4s.{Hints, Newtype, Schema, ShapeId}
import smithy4s.schema.Schema.{bijection, string}

object CityId extends Newtype[String] {
  val id: ShapeId                      = ShapeId("weather", "CityId")
  val hints: Hints                     = Hints.empty
  val underlyingSchema: Schema[String] = string.withId(id).addHints(hints)
  implicit val schema: Schema[CityId]  = bijection(underlyingSchema, asBijection)
}

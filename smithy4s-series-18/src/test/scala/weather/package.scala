/* Note that is generated code to avoid bringing in the codegen project from this smithy spec
```smithy
$version: "2"

namespace weather

use alloy#simpleRestJson

@simpleRestJson
service WeatherService {
    operations: [
        GetWeather
        CreateCity
    ]
    errors: [
        InternalServerError
    ]
}

// trait
@readonly
@documentation("Get the weather for a city")
@examples([
    {
        title: "Get the weather for a city"
        input: { cityId: "1", region: "Europe" }
        output: { weather: "sunny", degrees: 25 }
    }
])
@http(method: "GET", uri: "/cities/{cityId}/weather")
operation GetWeather {
    input := {
        @required
        @httpLabel
        cityId: CityId

        @httpQuery("X-Region")
        @required
        region: String
    }

    output := {
        @required
        weather: String

        // Optional
        degrees: Integer

        @httpResponseCode
        statusCode: Integer = 200
    }

    errors: [
        CityNotFoundError
    ]
}

// client = 4xx (who did something wrong - the client looked up something that doesn't exist)
@error("client")
@httpError(404)
structure CityNotFoundError {}

// server = 5xx (who did something wrong - the server is down)
@error("server")
@httpError(500)
structure InternalServerError {
    reason: String
}

@http(method: "POST", uri: "/cities", code: 201)
operation CreateCity {
    input := {
        @required
        city: String

        @required
        country: String
    }

    output := {
        @required
        cityId: CityId
    }
}

// newtype/opaque type in Scala
string CityId
```
 */
package object weather {
  type CityId = CityId.Type

  type WeatherService[F[_]] = smithy4s.kinds.FunctorAlgebra[WeatherServiceGen, F]
  val WeatherService = WeatherServiceGen
}

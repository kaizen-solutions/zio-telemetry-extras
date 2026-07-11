# ZIO Telemetry Extras

OpenTelemetry integrations built for the Scala ecosystem using [zio-telemetry](https://github.com/zio/zio-telemetry)

## http4s

The http4s module provides client and server middleware for `http4s` applications using `ZIO`.

## http4s-metrics

The http4s-metrics module provides the `MetricOps` abstraction built on ZIO metrics needed by (client and server) http4s Metrics middleware

## smithy4s (0.18.x and 0.19.x)

The smithy4s module provides client endpoint middleware and server endpoint middleware for `smithy4s` applications using `ZIO`.

- Server endpoint middleware works together with http4s server middleware to enrich the existing span with Smithy attributes
- Standalone Server endpoint middleware
- Client endpoint middleware is standalone middleware

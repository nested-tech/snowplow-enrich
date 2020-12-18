# Nested changes

**Please read the documentation below to get an understanding of the enrichment step of snowplow. Instruction here are just to run the job**

This repo holds the projects that pick raw data from the collector and enrich it ready to be sent to BQ.

We use the `beam` project (found on the folder by the same name inside `modules`) this is a Dataflow stream job that pick data from the raw PubSub queue and enrich the data and dumps it into another PubSub.

The enrichment process requires a `iglu resolver` config, a sort of central for definitons of schemas (there is already one maintained by snowplow with quite an extensive collection) and a json config per enrichment.

Both this requirements can be found on the `config` folder.

## Pre-Requisites

- Have sbt installed
- Have JRE (Java Runtime Engine) on your machine
- Have access to the GCP console

_Note: Access to the GCP console should be a given if you work on nested projects, hence not covered here_

### Install sbt

This can be achieve with asdf.

```sh
# First install the plugin
asdf plugin add sbt https://github.com/bram2000/asdf-sbt.git

# Then install the sbt version 1.3.12
asdf install sbt 1.3.12
```

### JRE

You should have JRE installed on the machine, if this is not the case but you have installed sbt with the above method you should see a link to download JRE.

If not just go onto http://java.com/en/download

## Build

To build projects use the sbt command line

```sh
sbt "project beam" universal"packageBuild
```

This will create a target/universal folder inside the loader project, which should have a zip file. This is the project packaged with a helpful scripts to run it.

## Run

To run the project we need to unpack that zip file that we built and use the script inside the bin folder that goes by the same name as the project.

```sh
./bin/beam-enrich \
  --runner=DataFlowRunner \
  --project=steadfast-range-119414 \
  --streaming=true \
  --region=europe-west1 \
  --gcpTempLocation=gs://nested-snowplow-test/tmp/ \
  --job-name=<whatever_you_want> \
  --raw=projects/steadfast-range-119414/subscriptions/enrichment-raw \
  --enriched=projects/steadfast-range-119414/topics/snowplow-good-enriched \
  --bad=projects/steadfast-range-119414/topics/snowplow-bad-enriched \
  --resolver=./config/iglu_resolver.json \
  --enrichments=./config/enrichments/
```

## Known quirks/issues

1. The data pointed by some enrichment configurations had to be made available on a gcs bucket we own. For some unknown reason the job could not get resources from `http(s)` or public `s3`.

2. Better research on what enrichments to use, might be needed, as I've just turned on a few I thought were needed.

3. The existance of a enrichement config file does not mean that enrichment will be used, its the `enabled` field on the config that controls that.

4. I couldn't read the iglu resolver or enrichments config from a gcs bucket, apparently it needs to be local to the code at the time the job is posted to DataFlow. Good luck cracking that nut 😅

[![License][license-image]][license]
[![Coverage Status][coveralls-image]][coveralls]
[![Test][test-image]][test]

# Snowplow Enrich

Snowplow Enrich is a set of applications and libraries for processing raw Snowplow events into validated and enriched Snowplow events, ready for loading into [Storage][storage].
It consists of following modules:

- Snowplow Common Enrich - a core library, containing all validation and transformation logic. Published on Maven Central
- Snowplow Stream Enrich - a set of applications working with Kinesis, Kafka and NSQ. Each asset published as Docker image on DockerHub
- Snowplow Beam Enrich - a Google Dataflow job. Published as Docker image on DockerHub

Snowplow Enrich provides record-level enrichment only: feeding in 1 raw Snowplow event will yield 0 or 1 records out, where a record may be an enriched Snowplow event or a reported bad record.

## Find out more

| Technical Docs             | Setup Guide          | Roadmap              | Contributing                 |
| -------------------------- | -------------------- | -------------------- | ---------------------------- |
| ![i1][techdocs-image]      | ![i2][setup-image]   | ![i3][roadmap-image] | ![i4][contributing-image]    |
| [Technical Docs][techdocs] | [Setup Guide][setup] | [Roadmap][roadmap]   | [Contributing][contributing] |

## Copyright and license

Scala Common Enrich is copyright 2012-2020 Snowplow Analytics Ltd.

Licensed under the [Apache License, Version 2.0][license] (the "License");
you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

[storage]: https://docs.snowplowanalytics.com/docs/setup-snowplow-on-aws/setup-destinations/
[techdocs-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/techdocs.png
[setup-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/setup.png
[roadmap-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/roadmap.png
[contributing-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/contributing.png
[techdocs]: https://docs.snowplowanalytics.com/open-source-docs/
[setup]: https://docs.snowplowanalytics.com/docs/setup-snowplow-on-aws/setup-validation-and-enrich/
[roadmap]: https://github.com/snowplow/enrich/issues
[contributing]: https://github.com/snowplow/snowplow/wiki/Contributing
[test]: https://github.com/snowplow/enrich/actions?query=workflow%3ATest
[test-image]: https://github.com/snowplow/enrich/workflows/Test/badge.svg
[license]: http://www.apache.org/licenses/LICENSE-2.0
[license-image]: http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat
[coveralls]: https://coveralls.io/github/snowplow/enrich?branch=master
[coveralls-image]: https://coveralls.io/repos/github/snowplow/enrich/badge.svg?branch=master

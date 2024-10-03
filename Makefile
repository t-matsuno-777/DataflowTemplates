MVN_OPTS=
MVN=mvn $(MVN_OPTS)
SUFFIX=$(shell git describe --tags --always --dirty)

.PHONY: clean
clean:
	$(MVN) clean

.PHONY: build
build:
	$(MVN) -pl v2 install -Dmaven.test.skip=true -Djib.skip=true
	rm -rf v2/googlecloud-to-googlecloud/target/*.jar
	$(MVN) -pl v2/googlecloud-to-googlecloud package -Dmaven.test.skip=true -Djib.skip=true
	[ -e v2/googlecloud-to-googlecloud/target/googlecloud-to-googlecloud-1.0-SNAPSHOT.jar ] \
	  && mv v2/googlecloud-to-googlecloud/target/googlecloud-to-googlecloud-1.0-SNAPSHOT.jar v2/googlecloud-to-googlecloud/target/googlecloud-to-googlecloud-1.0-SNAPSHOT-$(SUFFIX).jar
	ls -l v2/googlecloud-to-googlecloud/target/*.jar

.PHONY: run
run:
	java -cp v2/googlecloud-to-googlecloud/target/googlecloud-to-googlecloud-1.0-SNAPSHOT-$(SUFFIX).jar \
	  com.google.cloud.teleport.v2.templates.SpannerChangeStreamsToPubSub \
	    --spannerInstanceId=local \
	    --spannerDatabase=local-XXX \
		--spannerMetadataInstanceId=local \
		--spannerMetadataDatabase=local-XXX \
		--spannerChangeStreamName=QueueJobsChangeStream \
		--pubsubTopic=QueueJobs \
		--spannerHost=http://spanner-emulator:9010 \
	

.PHONY: deps-suggest
deps-suggest:
	$(MVN) versions:display-dependency-updates

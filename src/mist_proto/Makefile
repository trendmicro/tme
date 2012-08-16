
SRCDIR = src

proto:  
	cd ../../3rd_party/ ; make protobuf
	env LD_LIBRARY_PATH=../../output/works/protobuf-2.1.0/src/.libs ../../output/works/protobuf-2.1.0/src/.libs/protoc --java_out=$(SRCDIR) GateTalk.proto
	env LD_LIBRARY_PATH=../../output/works/protobuf-2.1.0/src/.libs ../../output/works/protobuf-2.1.0/src/.libs/protoc --java_out=$(SRCDIR) MistMessage.proto
	env LD_LIBRARY_PATH=../../output/works/protobuf-2.1.0/src/.libs ../../output/works/protobuf-2.1.0/src/.libs/protoc --java_out=$(SRCDIR) ZooKeeperInfo.proto
	env LD_LIBRARY_PATH=../../output/works/protobuf-2.1.0/src/.libs ../../output/works/protobuf-2.1.0/src/.libs/protoc --java_out=$(SRCDIR) BridgeTalk.proto
	@ant

clean:  
	@ant clean

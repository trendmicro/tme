#include "mist_core.h"

#include<iostream>
#include<arpa/inet.h>
#include<getopt.h>

using namespace std;

enum process_mode{
	HELP,
	LINE,
	STREAM,
};

void usage() {
	cerr << "Allowed options:" << endl
	<< "-h [ --help ]	Display help messages" << endl
	<< "-l [ --line ]	Decode message stream into lines" << endl
	<< "-s [ --stream ]	Decode message stream into [length][payload] format," << endl
	<< "		length is 4 byte big endian integer" << endl;
}

int main(int argc, char* argv[]) {
	int bflag, ch, fd;
	static struct option longopts[] = {
		{ "help", no_argument, NULL, 'h'},
		{ "line", no_argument, NULL, 'l'},
		{ "stream", no_argument, NULL, 's'},
	};
	bflag = 0;

	enum process_mode mode = HELP;

	while((ch = getopt_long(argc, argv, "hls", longopts, NULL)) != -1){
		switch(ch){
		case 'h':
			usage();
			break;
		case 'l':
			mode = LINE;
			break;
		case 's':
			mode = STREAM;
			break;
		case '?':
		default:
			usage();
		}
	}

	if(mode == LINE){
		Processor<Block_Policy_MessageBlock, Block_Policy_Line, Read_Stdin_Policy, Write_Stdout_Policy> processor;
		processor.run();
	}
	else if(mode == STREAM){
		Processor<Block_Policy_MessageBlock, Block_Policy_Length, Read_Stdin_Policy, Write_Stdout_Policy> processor;
		processor.run();
	}
	else{
		usage();
		return 1;
	}
	return 0;
}

#include "mist_core.h"

#include<string>
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
        << "-h [ --help ]       Display help messages" << endl
        << "-l [ --line ]       Encode each line as a message" << endl
        << "-s [ --stream ]     Encode message stream from [length][payload] format," << endl
        << "			length is 4 byte big endian integer" << endl
	<< "-w [ --wrap ] arg	Set the message's destination ID" << endl
	<< "-t [ --ttl ] arg	Set the message's ttl (seconds)" << endl;

}

int main(int argc, char* argv[]) {
        int bflag, ch, fd;
        static struct option longopts[] = {
                { "help", no_argument, NULL, 'h'},
                { "wrap", required_argument, NULL, 'w'},
                { "ttl", optional_argument, NULL, 't'},
                { "line", no_argument, NULL, 'l'},
                { "stream", no_argument, NULL, 's'},
        };
        bflag = 0;

        enum process_mode mode = HELP;
	string exchange_name = "";
	int ttl = -1;

        while((ch = getopt_long(argc, argv, "hw:t:ls", longopts, NULL)) != -1){
                switch(ch){
                case 'h':
                        usage();
                        break;
		case 'w':
			exchange_name = string(optarg);
			break;
                case 'l':
                        mode = LINE;
                        break;
                case 's':
                        mode = STREAM;
                        break;
		case 't':
			ttl = strtol(optarg, NULL, 10);
			break;
		case '?':
                default:
                        usage();
                }
        }

	if(exchange_name.empty()){
		cerr << "No destination ID is set! The wrap option is required" << endl;
		usage();
		return 1;
	}

        if(mode == LINE){
		Processor<Block_Policy_Line, Block_Policy_MessageBlock, Read_Stdin_Policy, Write_Stdout_Policy> processor;
		processor.set_id(exchange_name);
		if(ttl > 0){
			processor.set_ttl(ttl);
		}
                processor.run();
        }
        else if(mode == STREAM){
		Processor<Block_Policy_Length, Block_Policy_MessageBlock, Read_Stdin_Policy, Write_Stdout_Policy> processor;
		processor.set_id(exchange_name);
		if(ttl > 0){
			processor.set_ttl(ttl);
		}
                processor.run();
        }
        else{
                usage();
                return 1;
        }
        return 0;
}

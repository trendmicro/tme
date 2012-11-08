#include "mist_core.h"

#include<iostream>
#include<fstream>
#include<getopt.h>
#include<map>
#include<ctime>

using namespace std;

map<string, int> count_map;
ostream *logstream_ptr = &cerr;
int interval = 1;

void print_and_reset() {
    char time_str[100];
    time_t ts = time(NULL);
    std::strftime(time_str, 100, "[%Y-%m-%d %H:%M:%S] ", localtime(&ts));
    for(map<string, int>::iterator iter = count_map.begin(); iter != count_map.end(); ++iter){
	*logstream_ptr << time_str << iter->first << " : " << iter->second << endl;
    }
    logstream_ptr->flush();
    count_map.clear();
}

void count_loop() {
    time_t last_ts = time(NULL);
    Processor<Block_Policy_MessageBlock, Block_Policy_MessageBlock, Read_Stdin_Policy, Write_Stdout_Policy> p;
    while(p.process_one()){
	const string& id = static_cast<Read_Stdin_Policy<Block_Policy_MessageBlock>&>(p).get_id();
	count_map[id]++;
	if(time(NULL) - last_ts >= interval){
	    print_and_reset();
	    last_ts = time(NULL);
	}
    }
    print_and_reset();
}

void usage() {
	cerr << "Allowed options:" << endl
	<< "-i [ --interval ] arg	Minimum metric printing interval (second), default is 1" << endl
	<< "-f [ --file ] arg	Metric output file, default is stderr" << endl
	<< "-a [ --append ] 	Append metric to file instead of truncating it" << endl
	<< "-h [ --help ]	Display help messages" << endl;
}

int main(int argc, char* argv[]) {
	int bflag, ch, fd;
	bool is_file = false, append = false;
	string filename;
	static struct option longopts[] = {
		{ "interval", required_argument, NULL, 'i'},
		{ "file", required_argument, NULL, 'f'},
		{ "append", no_argument, NULL, 'a'},
		{ "help", no_argument, NULL, 'h'},
	};
	bflag = 0;

	while((ch = getopt_long(argc, argv, "i:f:ah", longopts, NULL)) != -1){
		switch(ch){
		case 'h':
			usage();
			return 0;
		case 'i':
			interval = strtol(optarg, NULL, 10);
			break;
		case 'f':
			is_file = true;
			filename = optarg;
			break;
		case 'a':
			append = true;
			break;
		case '?':
		default:
			usage();
			return MIST_ARGUMENT_ERROR;
		}
	}
	if(is_file){
		logstream_ptr = new ofstream(filename.c_str(), append ? ios_base::out | ios_base::app : ios_base::out | ios_base::trunc);
		if(logstream_ptr->fail()){
			cerr << "cannot open file " << filename << endl;
			return MIST_ARGUMENT_ERROR;
		}
	}
	count_loop();
	return 0;
}

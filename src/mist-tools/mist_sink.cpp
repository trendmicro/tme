#include "mist_core.h"

#include<iostream>
#include<fstream>
#include<stdlib.h>

#include<arpa/inet.h>
#include<netinet/in.h>
#include<sys/socket.h>
#include<netdb.h>
#include<netinet/tcp.h>
#include<sys/file.h>
#include<signal.h>
#include<unistd.h>
#include<getopt.h>

using namespace std;
using namespace com::trendmicro::mist::proto;


string ack_session_id;

void manual_ack_loop(istream& is, int sock){
	const uint32_t ACK_HEADER = ntohl(*((uint32_t*) &"ACK\n"));
	int pid = -1;
	for (;;) {
		uint32_t besize;
		if (!cin.read((char*) &besize, 4)) {
			break;
		}

		if (pid < 0) {
			string filename = string("/var/run/tme/pid/") + ack_session_id
					+ string(".pid");
			ifstream ifs(filename.c_str());
			ifs>>pid;
			ifs.close();
			cerr<<"pid="<<pid<<endl;
			cerr.flush();
		}

		if(ntohl(besize) == ACK_HEADER){
			cout.flush();
			kill(pid, SIGUSR1);
			continue;
		}


		char buf[1024];
		ssize_t total = ntohl(besize);
		ssize_t nwrite = 0;
		write(sock, (char*) &besize, 4);
		while (nwrite != total) {
			ssize_t size_to_read = (total - nwrite) < 1024 ? total - nwrite : 1024;
			cin.read(buf, size_to_read);
			write(sock, buf, size_to_read);
			nwrite += size_to_read;
			//cerr<<"n: "<<n<<" nw:"<<nw<<" nread:"<<nread<<endl;
		}

		if (read(sock, &besize, 4) <= 0){
			break;
		}
		total = ntohl(besize);
		ssize_t nread = 0;
		while (nread != total) {
			ssize_t n = read(sock, buf,
					(total - nread) < 1024 ? total - nread : 1024);
			nread += n;
			//cerr<<"n: "<<n<<" nw:"<<nw<<" nread:"<<nread<<endl;
		}



	}
}

bool attach(const string& session_id, bool ack, bool counting) {
	Command req_cmd;
	Command res;
	Request* req_ptr = req_cmd.add_request();
	req_ptr->set_type(Request::CLIENT_ATTACH);
	req_ptr->set_argument(session_id);
	req_ptr->set_role(Request::SINK);

	unsigned long count = 0;
	Response ackResponse;
	ackResponse.set_success(true);
	uint32_t ackSize = htonl(ackResponse.ByteSize());
	if (sendRequest(req_cmd, res)) {
		if (res.response(0).success()) {

			int sock = connectTo(atoi(res.response(0).context().c_str()));
			int trueflag = 1;
			setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &trueflag, sizeof(int));

			if(ack){
				manual_ack_loop(cin, sock);
			}
			else{
				Processor<Block_Policy_Length, Block_Policy_Length, Read_Stdin_Policy, Write_Socket_Policy> processor;
				Processor<Block_Policy_Skip, Block_Policy_Length, Read_Socket_Policy, Write_Stdout_Policy> response_processor;
				processor.set_sock_fd(sock);
				response_processor.set_sock_fd(sock);
				while(processor.process_one()){
					response_processor.process_one();
					if(counting){
						cerr<<++count<<" messages delivered"<<endl;
					}
				}
			}
			close(sock);
			return true;
		}
		else{
			cerr<<res.response(0).exception()<<endl;
		}
	}
	return false;

}

bool detach(const string& session_id) {
	Command req_cmd;
	Command res;
	Request* req_ptr = req_cmd.add_request();
	req_ptr->set_type(Request::CLIENT_DETACH);
	req_ptr->set_argument(session_id);
	if (sendRequest(req_cmd, res)){
            if (res.response(0).success()){
                cerr<<res.response(0).context()<<endl;
		return true;
            }
            else{
                cerr<<res.response(0).exception()<<endl;
            }
        }
	return false;
}

void usage() {
        cerr << "Usage: mist-sink [session id] OPTIONS" << endl;
        cerr << "Allowed options:" << endl
        << "-h [ --help ]	Display help messages" << endl
        << "-a [ --attach ]	Attach session" << endl
        << "-d [ --detach ]	Detach session" << endl
        << "-c [ --count ]	Output delivered message count" << endl
        << "-A [ --ack ] pid	Manual ACK messages" << endl;
}

enum process_mode{
        HELP,
	ATTACH,
	DETACH,
};

int main(int argc, char* argv[]) {
        int bflag, ch, fd;
        static struct option longopts[] = {
                { "help", no_argument, NULL, 'h'},
                { "attach", no_argument, NULL, 'a'},
                { "detach", no_argument, NULL, 'd'},
                { "count", no_argument, NULL, 'c'},
                { "ack", no_argument, NULL, 'A'},
        };
        bflag = 0;
	
	if(argc < 3){
		usage();
		return MIST_ARGUMENT_ERROR;
	}
	string session_id = string(argv[1]);
	enum process_mode mode = HELP;
	bool counting = false;
	bool acking = false;

        while((ch = getopt_long(argc, argv, "hadcA", longopts, NULL)) != -1){
                switch(ch){                
		case 'h':
                        usage();
                        break;
                case 'a':
			mode = ATTACH;
                        break;
                case 'd': 
			mode = DETACH;
                        break;
                case 'c':
			counting = true;
                        break;
                case 'A':
			acking = true;
                        break;
                case '?':
                default:
                        usage();
                }
        }

	switch(mode){
	case ATTACH:
		if(!attach(session_id, acking, counting)){
			return MIST_SINK_ATTACH_ERROR;
		}
		break;
	case DETACH:
		if(!detach(session_id)){
			return MIST_SINK_DETACH_ERROR;
		}
		break;
	case HELP:
	default:
		usage();
		return MIST_ARGUMENT_ERROR;
	}
	return 0;
}

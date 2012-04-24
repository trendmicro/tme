#include "mist_core.h"

#include<iostream>
#include<fstream>
#include<netinet/tcp.h>
#include<signal.h>
#include<unistd.h>
#include<getopt.h>

using namespace std;
using namespace com::trendmicro::mist::proto;

bool on_close = false;

int sock;

bool attach(const string& session_id, bool ack, long limit){
	long msg_cnt = 0;
	string filename = string("/var/run/tme/pid/") + session_id + string(".pid");

	ofstream ofs(filename.c_str());
	ofs << getpid() << endl;
	ofs.close();

	Command req_cmd;
	Command res;
	Request* req_ptr = req_cmd.add_request();
	req_ptr->set_type(Request::CLIENT_ATTACH);
	req_ptr->set_argument(session_id);
	req_ptr->set_role(Request::SOURCE);

	Response ackResponse;
	ackResponse.set_success(true);
	uint32_t ackSize=htonl(ackResponse.ByteSize());
	if (sendRequest(req_cmd, res)) {
		if (res.response(0).success()) {
			sock = connectTo(atoi(res.response(0).context().c_str()));
			int trueflag = 1;
			setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &trueflag, sizeof(int));

			Processor<Block_Policy_Length, Block_Policy_Length, Read_Socket_Policy, Write_Stdout_Policy> processor;
			processor.set_sock_fd(sock);
			
			while(!on_close && processor.process_one()){
				if(ack){
					sigset_t waitset;
					int sig;
					int result;
					sigemptyset(&waitset);
					sigaddset(&waitset, SIGUSR1);
					sigprocmask(SIG_BLOCK, &waitset, NULL);
					result = sigwait(&waitset, &sig);
				}
				write(sock,&ackSize,4);
				ackResponse.SerializeToFileDescriptor(sock);

				if(++msg_cnt == limit){
					on_close = true;
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

bool detach(const string& session_id){
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

bool mount(const string& session_id, const string& exName) {
	Command cmd;
	Client* clientPtr = cmd.add_client();
	clientPtr->set_session_id(atoi(session_id.c_str()));
	clientPtr->mutable_channel()->set_name(exName);
	clientPtr->mutable_channel()->set_type(Channel::QUEUE);
	clientPtr->set_type(Client::CONSUMER);
	clientPtr->set_action(Client::MOUNT);

	Command res;
	if (sendRequest(cmd, res)){
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

bool unmount(const string& session_id, const string& exName) {
	Command cmd;
	Client* clientPtr = cmd.add_client();
	clientPtr->set_session_id(atoi(session_id.c_str()));
	clientPtr->mutable_channel()->set_name(exName);
	clientPtr->mutable_channel()->set_type(Channel::QUEUE);
	clientPtr->set_type(Client::CONSUMER);
	clientPtr->set_action(Client::UNMOUNT);

	Command res;
	if (sendRequest(cmd, res)){
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

string session_id;

void cleanup() {
	unlink((string("/var/run/tme/pid/") + session_id + string(".pid")).c_str());
}

void handler(int signo){
	close(sock);
	on_close = true;
}

void usage() {
        cerr << "Usage: mist-source [session id] OPTIONS" << endl;
        cerr << "Allowed options:" << endl
        << "-h [ --help ]	Display help messages" << endl
        << "-a [ --attach ]	Attach session" << endl
        << "-d [ --detach ]	Detach session" << endl
        << "-m [ --mount ] arg	Mount exchange to session" << endl
        << "-u [ --unmount ] arg	Unmount exchange from session"<< endl
        << "-l [ --limit ] arg	Retrieve count limit"<< endl
        << "-A [ --ack ] pid    Manual ACK messages" << endl;
}

enum process_mode{
        HELP,
        ATTACH,
        DETACH,
	MOUNT,
	UNMOUNT,
};

int main(int argc, char* argv[]) {
	atexit(cleanup);
	signal(SIGINT, handler);

        int bflag, ch, fd;
        static struct option longopts[] = {
                { "help", no_argument, NULL, 'h'},
                { "attach", no_argument, NULL, 'a'},
                { "detach", no_argument, NULL, 'd'},
                { "limit", required_argument, NULL, 'l'},
                { "mount", required_argument, NULL, 'm'},
                { "unmount", required_argument, NULL, 'u'},
                { "ack", no_argument, NULL, 'A'},
        };
        bflag = 0;

        if(argc < 3){
                usage();
                return MIST_ARGUMENT_ERROR;
        }
        string session_id = string(argv[1]);
        enum process_mode mode = HELP;
        bool acking = false;
	int limit = -1;
	string exchange_name = "";

        while((ch = getopt_long(argc, argv, "hadl:m:u:A", longopts, NULL)) != -1){
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
                case 'l':
                        limit = strtol(optarg, NULL, 10);
                        break;
                case 'm':
			mode = MOUNT;
                        exchange_name = string(optarg);
                        break;
                case 'u':
			mode = UNMOUNT;
                        exchange_name = string(optarg);
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
                if(!attach(session_id, acking, limit > 0 ? limit : -1)){
                        return MIST_SOURCE_ATTACH_ERROR;
                }
                break;
        case DETACH:
                if(!detach(session_id)){
                        return MIST_SOURCE_DETACH_ERROR;
                }
                break;
        case MOUNT:
		if(!mount(session_id, exchange_name)){
			return MIST_SOURCE_MOUNT_ERROR;
		}
                break;
        case UNMOUNT:
		if(!unmount(session_id, exchange_name)){
			return MIST_SOURCE_UNMOUNT_ERROR;
		}
                break;
        case HELP:
        default:
                usage();
                return MIST_ARGUMENT_ERROR;
        }
	return 0;
}

#include "mist_core.h"

#include<iostream>
#include<fstream>
#include<getopt.h>

using namespace com::trendmicro::mist::proto;
using namespace std;

void usage() {
        cerr << "Allowed options:" << endl
        << "-h [ --help ]	Display help messages" << endl
        << "-d [ --destroy ] id	Destroy session" << endl
        << "-l [ --list ]	List all sessions" << endl
        << "-s [ --status ]	Show MIST daemon status" << endl;
}


int create_session() {
	int session_id = -1;

	Command cmd;
	Session* sessPtr = cmd.add_session();
	sessPtr->mutable_connection()->set_host_name("");
	sessPtr->mutable_connection()->set_host_port("");
	sessPtr->mutable_connection()->set_username("");
	sessPtr->mutable_connection()->set_password("");
	sessPtr->mutable_connection()->set_broker_type("");

	Command res;
	if (sendRequest(cmd, res)){
		if (res.response(0).success()){
			session_id = atoi(res.response(0).context().c_str());
		}
		else{
			cerr<<res.response(0).exception()<<endl;
		}
	}
	return session_id;
}

bool destroy_session(const string& session_id) {
	Command cmd;
	Request* reqPtr = cmd.add_request();
	reqPtr->set_type(Request::SESSION_DESTROY);
	reqPtr->set_argument(session_id);

	Command res;
	if (sendRequest(cmd, res)) {
		if (res.response(0).success()) {
			cerr<<res.response(0).context()<<endl;
			return true;
		}
		else{
			cerr<<res.response(0).exception()<<endl;
		}
	}
	return false;
}

bool list_session() {
	Command cmd;
	Request* reqPtr = cmd.add_request();
	reqPtr->set_type(Request::SESSION_LIST);

	Command res;
	if (sendRequest(cmd, res)) {
		if (res.response(0).success()) {
			cout<<res.response(0).context()<<endl;
			return true;
		}
		else{
			cerr<<res.response(0).exception()<<endl;
		}
	}
	return false;
}

bool show_status() {
	Command cmd;
	Request* reqPtr = cmd.add_request();
	reqPtr->set_type(Request::DAEMON_STATUS);

	Command res;
	if (sendRequest(cmd, res)) {
		if (res.response(0).success()) {
			cout<<res.response(0).context()<<endl;
			return true;
		}
		else{
			cerr<<res.response(0).exception()<<endl;
		}
	}
	return false;
}

int main(int argc, char* argv[]) {
        int bflag, ch, fd;
        static struct option longopts[] = {
                { "help", no_argument, NULL, 'h'},
                { "destroy", required_argument, NULL, 'd'},
                { "list", no_argument, NULL, 'l'},
                { "status", no_argument, NULL, 's'},
        };
        bflag = 0;

	if(argc == 1){
		int sess_id = create_session();
		if(sess_id != -1){
			cout << sess_id << endl;
			return 0;
		}
		else{
			return MIST_SESSION_CREATE_ERROR;
		}
	}

	while((ch = getopt_long(argc, argv, "hd:ls", longopts, NULL)) != -1){
                switch(ch){
                case 'h':
                        usage();
                        break;
                case 'd':
			if(!destroy_session(optarg)){
				return MIST_SESSION_DESTROY_ERROR;
			}
			break;
                case 'l':
			if(!list_session()){
				return MIST_SESSION_LIST_ERROR;
			}
			break;
                case 's':
			if(!show_status()){
				return MIST_SESSION_STATUS_ERROR;
			}
			break;
		case '?':
                default:
			usage();
			break;
                }
        }
	return 0;
}

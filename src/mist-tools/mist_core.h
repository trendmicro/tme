#ifndef _MIST_CORE
#define _MIST_CORE

#define MAX_MSG_SIZE 20 * 1024 * 1024
#define MISTD_PORT 9498

#define MIST_ARGUMENT_ERROR 134

#define MIST_SESSION_CREATE_ERROR 1
#define MIST_SESSION_DESTROY_ERROR 2
#define MIST_SESSION_LIST_ERROR 3
#define MIST_SESSION_STATUS_ERROR 4

#define MIST_SOURCE_MOUNT_ERROR 1
#define MIST_SOURCE_UNMOUNT_ERROR 2
#define MIST_SOURCE_ATTACH_ERROR 3
#define MIST_SOURCE_DETACH_ERROR 4

#define MIST_SINK_ATTACH_ERROR 3
#define MIST_SINK_DETACH_ERROR 4

#include <MistMessage.pb.h>
#include <GateTalk.pb.h>
#include <iostream>
#include <arpa/inet.h>
#include <errno.h>

int read_all(int blocking_fd, char* buf, size_t size) {
    size_t size_read = 0;
    while (size_read < size) {
        int n = read(blocking_fd, buf + size_read, size - size_read);
        if (n <= 0)
            return n;
        size_read += n;
    }

    return size_read;
}

bool write_all(int blocking_fd, const char* buf, ssize_t size) {
    ssize_t size_write = 0;
    while (size_write < size) {
	ssize_t n = write(blocking_fd, buf + size_write, size - size_write);
	if (n == -1){
	    std::cerr << "Write error: " << strerror(errno) << std::endl;
	    return false;
	}
	size_write += n;
    }
    return true;
}

int connectTo(int port){
    int sock=socket(AF_INET,SOCK_STREAM,0);
    struct sockaddr_in sa;
    sa.sin_family=AF_INET;
    inet_aton("127.0.0.1",(struct in_addr *)&sa.sin_addr.s_addr);
    sa.sin_port=htons(port);

    socklen_t socklen = sizeof(sa);
    if((connect(sock,(struct sockaddr*)&sa,sizeof(sa)) < 0) ||
	(getsockname(sock, (struct sockaddr*)&sa, &socklen) != 0) ||
	/* prevent connect to self */ (ntohs(sa.sin_port) == MISTD_PORT)){
        close(sock);
        return -1;
    }
    else
        return sock;
}

bool sendRequest(const com::trendmicro::mist::proto::Command& req, com::trendmicro::mist::proto::Command& res){
    int sock;
    if((sock=connectTo(MISTD_PORT))<0){
	std::cerr<<"Error connecting to MIST daemon!"<<std::endl;
        return false;
    }

    uint32_t byteSize=htonl(req.ByteSize());
    write(sock,&byteSize,4);
    req.SerializeToFileDescriptor(sock);

    read(sock,&byteSize,4);
    if(res.ParseFromFileDescriptor(sock)){
        close(sock);
        return true;
    }
    else{
	std::cerr<<"Error parsing response data!"<<std::endl;
        close(sock);
        return false;
    }
}

typedef struct message_payload{
	const char* buf;
	const size_t len;
	message_payload(const char* buf_, const size_t len_): buf(buf_), len(len_) {}
}message_payload;

template
<
	class source_block_policy,
	class sink_block_policy,
	template <class> class source_policy,
	template <class> class sink_policy
>
class Processor : 	public source_policy<source_block_policy>, 
					public sink_policy<sink_block_policy>
{
	using source_policy<source_block_policy>::Read;
	using sink_policy<sink_block_policy>::Write;

	public:
		bool process_one(){
			message_payload payload = Read();
			if(payload.len <= 0){
				return false;
			}
			return Write(payload.buf, payload.len);
		}

		void run(){
			for(;;){
				message_payload payload = Read();
				if(payload.len <= 0){
					break;
				}
				if(!Write(payload.buf, payload.len)){
				    break;
				}
			}
		}
};

class Block_Base{
	public:
		Block_Base(){
			_buf = new char[MAX_MSG_SIZE];
		}

		~Block_Base(){
			delete[] _buf;
		}

	protected:
		char* _buf;
};

template < class block_policy >
class Read_Stdin_Policy : public block_policy
{
	protected:
		message_payload Read(){
			return block_policy::Read(0);
		}
};

class Socket_Policy_Base{
	public:
		void set_sock_fd(int sock){
			_sock = sock;
		}

	protected:
		int _sock;
};

template < class block_policy >
class Read_Socket_Policy : public block_policy, public Socket_Policy_Base
{
	protected:
		message_payload Read(){
			return block_policy::Read(_sock);
		}
};

template < class block_policy >
class Write_Socket_Policy : public block_policy, public Socket_Policy_Base
{
	protected:
		bool Write(const char* buf, const size_t count){
			return block_policy::Write(_sock, buf, count);
		}
};

template < class block_policy >
class Write_Stdout_Policy : public block_policy
{
	protected:
		bool Write(const char* buf, const size_t count){
			return block_policy::Write(1, buf, count);
		}
};

class Block_Policy_Line : public Block_Base
{
	public:
		Block_Policy_Line():_linebuf_ptr(_linebuf), _linebuf_end(_linebuf){}

	protected:
		message_payload Read(const int fd){
			char* ptr = _buf;
			size_t size = 0;
			
			int c;
			while((c = get(fd)) != -1 && c != '\n'){
				if((size = ptr - _buf + 1) == MAX_MSG_SIZE){
					throw "Maximum message size exceeded";
				}
				*ptr++ = c;
			}
			*ptr = '\0';
			return message_payload(_buf, size);
		}

		bool Write(const int fd, const char* buf, const size_t count){
			return write_all(fd, buf, count) && write_all(fd, "\n", 1);
		}
	private:
		char _linebuf[1024];
		char* _linebuf_ptr;
		char* _linebuf_end;

		int get(const int fd){
			if(_linebuf_ptr == _linebuf_end){
				size_t nread = read(fd, _linebuf, 1024);
				if(nread <= 0){
					return -1;
				}
				_linebuf_end = _linebuf + nread;
				_linebuf_ptr = _linebuf;
			}
			return *_linebuf_ptr++;
		}
};

class Block_Policy_Length : public Block_Base
{
	protected:
		message_payload Read(const int fd){
			uint32_t len = 0;
			if(read_all(fd, _buf, 4) == 4){
				len = ntohl(*((uint32_t*)_buf));
				read_all(fd, _buf, len);
			}
			return message_payload(_buf, len);
		}

		bool Write(const int fd, const char* buf, const size_t count){
			uint32_t len = htonl(count);
			return write_all(fd, (char*)&len, 4) && write_all(fd, buf, count);
		}
};

class Block_Policy_Skip : public Block_Policy_Length
{
	protected:
		message_payload Read(const int fd){
			Block_Policy_Length::Read(fd);
			return message_payload(_buf, 0);
		}
};

class Block_Policy_MessageBlock : public Block_Policy_Length
{
	public:
		Block_Policy_MessageBlock() : _ttl(-1), _id("") {}

		void set_id(const std::string id){
			_id = id;
		}

		void set_ttl(const int ttl){
			_ttl = ttl;
		}
		
	protected:
		message_payload Read(const int fd){
			message_payload raw = Block_Policy_Length::Read(fd);
			if(raw.len == 0){
				return message_payload(_buf, 0);
			}
			else if(_msg.ParseFromArray(raw.buf, raw.len)) {
				return message_payload(_msg.message().data(), _msg.message().size());
			}
			else {
				std::cerr<<"Error parsing message!"<<std::endl;
				return message_payload(_buf, 0);
			}
		}

		bool Write(const int fd, const char* buf, const size_t count){
			_msg.set_id(_id);
			if(_ttl > 0){
			    _msg.set_ttl(_ttl * 1000);	// change from second to millisecond
			}
			_msg.set_message(buf, count);
			uint32_t len = htonl(_msg.ByteSize());
			return write_all(fd, (char*)&len, 4) && _msg.SerializeToFileDescriptor(fd);
		}

	private:
		std::string _id;
		int _ttl;
		com::trendmicro::mist::proto::MessageBlock _msg;
};

#endif


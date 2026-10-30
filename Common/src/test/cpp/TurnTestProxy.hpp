// Test-only TCP/TLS facade for the bundled UDP TURN server. Each stream gets
// its own UDP socket, preserving the allocation's five-tuple and permissions.
#pragma once
#include "net/ICE/TurnStreamFrames.hpp"
#include <openssl/ssl.h>
#include <poll.h>
#include <csignal>
#include <unistd.h>

class TurnTestProxy {
    int listener=-1;
    uint16_t service=0, target;
    SSL_CTX *context=nullptr;
    std::jthread acceptor;
    std::vector<std::jthread> clients;
    void client(std::stop_token stop,int stream) {
        int udp=socket(AF_INET,SOCK_DGRAM,0);
        sockaddr_in address{};
        address.sin_family=AF_INET;
        address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        address.sin_port=htons(target);
        connect(udp,reinterpret_cast<sockaddr *>(&address),sizeof(address));
        SSL *ssl=nullptr;
        if(context) {
            if(const char *delay=getenv("TURN_TEST_HANDSHAKE_DELAY_MS"))
                std::this_thread::sleep_for(std::chrono::milliseconds(std::stoi(delay)));
            timeval limit{.tv_sec=2};
            setsockopt(stream,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof(limit));
            ssl=SSL_new(context); SSL_set_fd(ssl,stream);
            if(SSL_accept(ssl)!=1) { SSL_free(ssl);close(stream);close(udp);return; }
        }
        std::vector<char> pending;
        std::array<char,65560> bytes{};
        while(!stop.stop_requested()) {
            pollfd sockets[2]{{.fd=stream,.events=POLLIN},{.fd=udp,.events=POLLIN}};
            if(poll(sockets,2,100)<0) break;
            if(sockets[0].revents&(POLLHUP|POLLERR)) break;
            if(sockets[0].revents&POLLIN) {
                const int count=ssl?SSL_read(ssl,bytes.data(),bytes.size()):recv(stream,bytes.data(),bytes.size(),0);
                if(count<=0) break;
                pending.insert(pending.end(),bytes.begin(),bytes.begin()+count);
                size_t consumed=0;
                while(consumed<pending.size()) {
                    auto frame=turnStreamFrame(std::span(pending).subspan(consumed));
                    if(!frame) { consumed=pending.size();break; }
                    if(!frame->wireBytes||frame->wireBytes>pending.size()-consumed) break;
                    send(udp,pending.data()+consumed,frame->payloadBytes,0);
                    consumed+=frame->wireBytes;
                }
                pending.erase(pending.begin(),pending.begin()+consumed);
            }
            if(sockets[1].revents&POLLIN) {
                const int count=recv(udp,bytes.data(),bytes.size()-3,0);
                if(count<=0) break;
                const auto frame=turnStreamFrame({bytes.data(),static_cast<size_t>(count)});
                if(!frame||!frame->wireBytes) break;
                std::fill(bytes.begin()+count,bytes.begin()+frame->wireBytes,0);
                const int written=ssl?SSL_write(ssl,bytes.data(),frame->wireBytes):
                                      send(stream,bytes.data(),frame->wireBytes,0);
                if(written!=static_cast<int>(frame->wireBytes)) break;
            }
        }
        if(ssl) { SSL_shutdown(ssl);SSL_free(ssl); }
        close(stream);close(udp);
    }
public:
    TurnTestProxy(uint16_t udpPort,bool tls):target(udpPort) {
        signal(SIGPIPE,SIG_IGN);
        if(tls) {
            context=SSL_CTX_new(TLS_server_method());
            assert(context&&SSL_CTX_use_certificate_file(context,getenv("TURN_TEST_CERT"),SSL_FILETYPE_PEM)==1);
            assert(SSL_CTX_use_PrivateKey_file(context,getenv("TURN_TEST_KEY"),SSL_FILETYPE_PEM)==1);
        }
        listener=socket(AF_INET,SOCK_STREAM,0);assert(listener>=0);
        sockaddr_in address{};
        address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        assert(::bind(listener,reinterpret_cast<sockaddr *>(&address),sizeof(address))==0);
        socklen_t length=sizeof(address);
        assert(getsockname(listener,reinterpret_cast<sockaddr *>(&address),&length)==0);
        service=ntohs(address.sin_port);assert(listen(listener,4)==0);
        acceptor=std::jthread([this](std::stop_token stop) {
            while(!stop.stop_requested()) {
                pollfd descriptor{.fd=listener,.events=POLLIN};
                if(poll(&descriptor,1,100)<=0) continue;
                const int stream=accept(listener,nullptr,nullptr);
                if(stream>=0) clients.emplace_back([this,stream](std::stop_token token) {client(token,stream);});
            }
        });
    }
    ~TurnTestProxy() {
        acceptor.request_stop();acceptor.join();close(listener);
        for(auto &thread:clients) thread.request_stop();
        clients.clear();
        if(context) SSL_CTX_free(context);
    }
    uint16_t port() const { return service; }
};

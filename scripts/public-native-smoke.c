#include "Limelight.h"
#include "PlatformCrypto.h"
#include <openssl/ssl.h>
#include <openssl/crypto.h>
#include <opus/opus.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <unistd.h>

CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAILED line %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main(void) {
    unsigned char key[16], iv[16], input[64], cipher[128], output[128], tag[16];
    int encrypted = 0, decrypted = 0;
    PltGenerateRandomData(key, sizeof(key));
    PltGenerateRandomData(iv, sizeof(iv));
    for (int i=0;i<64;i++) input[i]=(unsigned char)(i+1);
    PPLT_CRYPTO_CONTEXT enc=PltCreateCryptoContext(), dec=PltCreateCryptoContext();
    CHECK(enc && dec);
    CHECK(PltEncryptMessage(enc,ALGORITHM_AES_GCM,CIPHER_FLAG_RESET_IV|CIPHER_FLAG_FINISH,key,16,iv,12,tag,16,input,64,cipher,&encrypted));
    CHECK(PltDecryptMessage(dec,ALGORITHM_AES_GCM,CIPHER_FLAG_RESET_IV|CIPHER_FLAG_FINISH,key,16,iv,12,tag,16,cipher,encrypted,output,&decrypted));
    CHECK(decrypted==64 && memcmp(input,output,64)==0);
    PltDestroyCryptoContext(dec); dec=PltCreateCryptoContext();
    tag[0]^=1;
    CHECK(!PltDecryptMessage(dec,ALGORITHM_AES_GCM,CIPHER_FLAG_RESET_IV|CIPHER_FLAG_FINISH,key,16,iv,12,tag,16,cipher,encrypted,output,&decrypted));
    PltDestroyCryptoContext(enc); PltDestroyCryptoContext(dec);
    enc=PltCreateCryptoContext(); dec=PltCreateCryptoContext();
    CHECK(PltEncryptMessage(enc,ALGORITHM_AES_CBC,CIPHER_FLAG_RESET_IV|CIPHER_FLAG_FINISH,key,16,iv,16,NULL,0,input,64,cipher,&encrypted));
    CHECK(PltDecryptMessage(dec,ALGORITHM_AES_CBC,CIPHER_FLAG_RESET_IV|CIPHER_FLAG_FINISH,key,16,iv,16,NULL,0,cipher,encrypted,output,&decrypted));
    CHECK(decrypted==64 && memcmp(input,output,64)==0);
    PltDestroyCryptoContext(enc); PltDestroyCryptoContext(dec);
    SSL_CTX* tls=SSL_CTX_new(TLS_method()); CHECK(tls); SSL_CTX_free(tls);
    int err=0;
    OpusEncoder* oe=opus_encoder_create(48000,1,OPUS_APPLICATION_VOIP,&err); CHECK(oe && err==0);
    OpusDecoder* od=opus_decoder_create(48000,1,&err); CHECK(od && err==0);
    opus_int16 pcm[480], decoded[480]; unsigned char packet[1400];
    for(int i=0;i<480;i++) pcm[i]=(opus_int16)(8000*sin(2*3.141592653589793*440*i/48000));
    int bytes=opus_encode(oe,pcm,480,packet,sizeof(packet)); CHECK(bytes>0);
    CHECK(opus_decode(od,packet,bytes,decoded,480,0)==480);
    opus_encoder_destroy(oe); opus_decoder_destroy(od);
    printf("PASS: actual PlatformCrypto AES-GCM roundtrip/tamper, AES-CBC, RNG, TLS context, Opus encode/decode\n%s\n%s\nPAGE_SIZE=%ld\n",OpenSSL_version(OPENSSL_VERSION),opus_get_version_string(),sysconf(_SC_PAGESIZE));
    return 0;
}

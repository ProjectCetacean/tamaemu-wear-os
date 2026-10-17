/* Standalone foreground target: no sockets, IR peers, or NFC injection.
 * CPU and on-device peripherals remain upstream implementations. */
#include "emu.h"
uint64_t link_now_us(void) { return 0; }
void link_set_tx_byte_us(Link *l, unsigned us) { (void)l; (void)us; }
void link_tx(Link *l, int core, uint8_t byte) { (void)l; (void)core; (void)byte; }
int link_rx(Link *l, int core) { (void)l; (void)core; return -1; }
int link_rx_pending(Link *l, int core) { (void)l; (void)core; return 0; }
void link_reset(Link *l) { (void)l; }
void link_close(Link *l) { (void)l; }
int link_auto_begin(Link *l, int port) { (void)l; (void)port; return 0; }
int link_auto_poll(Link *l) { (void)l; return 0; }
void link_ir_pc_sample(Emu *e) { (void)e; }
void link_nfc_tx(Link *l, const uint8_t *p, int n) { (void)l; (void)p; (void)n; }
int link_nfc_rx(Link *l, uint8_t *p, int n) { (void)l; (void)p; (void)n; return 0; }
void nfcpeer_rf_in(Emu *e, const uint8_t *p, int n) { (void)e; (void)p; (void)n; }
void nfcpeer_tick(Emu *e) { (void)e; }

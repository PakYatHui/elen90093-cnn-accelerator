// =============================================================================
// conv_fixed_5x5_dump.c
// ELEN90093 Fixed16 5x5 Continuous Dump Test
//
// Purpose:
//   Run one 5x5 fixed16 no-bias convolution on MyConvAccel and dump selected
//   continuous output rows. This file uses the current accelerator interface:
//     CONFIG  rs1 = kernel_size, rs2 = data_type | bias_enable
//     KERNEL  rs1 = kernel_addr, rs2 = bias_addr
//     DATA    rs1 = input_addr,  rs2 = output_addr
//     COMPUTE no operands
//     STORE   no operands
//
// Fixed16 format: signed 8.8, so integer N is stored as N << 8.
// Input:  input[r][c] = fixed16(r + c)
// Kernel: all fixed16(1.0)
// Bias:   off
// =============================================================================

#include <stdint.h>
#include <stdio.h>
#include "rocc.h"

#define INPUT_SIZE   32
#define INPUT_ELEMS  (INPUT_SIZE * INPUT_SIZE)

// funct7 encodings. Must match ConvAccelerator.scala.
#define FUNCT_CONFIG   0
#define FUNCT_DATA     1
#define FUNCT_COMPUTE  2
#define FUNCT_STORE    3
#define FUNCT_KERNEL   4

// Data type encoding. Must match ConvAccelerator.scala.
#define DATA_FIXED16   0

// RoCC custom opcode index.
#define ROCC_X 0

#define TO_FIXED_INT(x) ((int16_t)((x) << 8))

static int16_t input_buf[INPUT_ELEMS]   __attribute__((aligned(64)));
static int16_t kernel_buf[25]           __attribute__((aligned(64)));
static int16_t output_buf[INPUT_ELEMS]  __attribute__((aligned(64)));
static int16_t bias_buf[1]              __attribute__((aligned(64)));

static inline uint64_t read_cycle(void) {
    uint64_t cycle;
    asm volatile ("rdcycle %0" : "=r"(cycle));
    return cycle;
}

static inline uint64_t conv_config(uint64_t kernel_size, uint64_t flags) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret, kernel_size, flags, FUNCT_CONFIG);
    return ret;
}

static inline uint64_t conv_kernel(const void *kernel_addr, const void *bias_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(
        ROCC_X,
        ret,
        (uint64_t)(uintptr_t)kernel_addr,
        (uint64_t)(uintptr_t)bias_addr,
        FUNCT_KERNEL
    );
    return ret;
}

static inline uint64_t conv_data(const void *input_addr, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(
        ROCC_X,
        ret,
        (uint64_t)(uintptr_t)input_addr,
        (uint64_t)(uintptr_t)output_addr,
        FUNCT_DATA
    );
    return ret;
}

static inline uint64_t conv_compute(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_COMPUTE);
    return ret;
}

static inline uint64_t conv_store(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_STORE);
    return ret;
}

static int16_t expected_5x5(int row, int col) {
    int32_t sum = 0;

    for (int kr = 0; kr < 5; kr++) {
        for (int kc = 0; kc < 5; kc++) {
            int in_row = row + kr - 2;
            int in_col = col + kc - 2;

            if (in_row >= 0 && in_row < INPUT_SIZE &&
                in_col >= 0 && in_col < INPUT_SIZE) {
                sum += (int32_t)TO_FIXED_INT(in_row + in_col);
            }
        }
    }

    return (int16_t)(sum & 0xffff);
}

static void init_buffers(void) {
    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            input_buf[row * INPUT_SIZE + col] = TO_FIXED_INT(row + col);
            output_buf[row * INPUT_SIZE + col] = 0;
        }
    }

    for (int i = 0; i < 25; i++) {
        kernel_buf[i] = TO_FIXED_INT(1);
    }

    bias_buf[0] = 0;
}

static void dump_row(int row) {
    printf("\n--- row %d: output[%d..%d] ---\n", row, row * INPUT_SIZE, row * INPUT_SIZE + 31);
    printf(" idx | row col | expected_raw | hw_raw | result\n");
    printf("-----|---------|--------------|--------|--------\n");

    for (int col = 0; col < INPUT_SIZE; col++) {
        int idx = row * INPUT_SIZE + col;
        uint16_t expected = (uint16_t)expected_5x5(row, col);
        uint16_t hw = (uint16_t)output_buf[idx];

        printf("%4d | %3d %3d | 0x%04x       | 0x%04x | %s\n",
               idx,
               row,
               col,
               expected,
               hw,
               (expected == hw) ? "PASS" : "FAIL");
    }
}

int main(void) {
    printf("=== ELEN90093 Fixed16 5x5 Continuous Dump Test ===\n");
    printf("Input: X[r][c] = fixed16(r+c), Kernel: all fixed16(1.0), Bias: off\n");

    init_buffers();

    uint64_t ret;
    uint64_t t0;
    uint64_t t1;

    asm volatile("fence rw, rw" ::: "memory");

    t0 = read_cycle();

    ret = conv_config(5, DATA_FIXED16);
    if (ret == 0) {
        printf("CONFIG failed\n");
        return 1;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_kernel(kernel_buf, bias_buf);
    if (ret == 0) {
        printf("KERNEL failed\n");
        return 1;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_data(input_buf, output_buf);
    if (ret == 0) {
        printf("DATA failed\n");
        return 1;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_compute();
    if (ret == 0) {
        printf("COMPUTE failed\n");
        return 1;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_store();
    if (ret == 0) {
        printf("STORE failed\n");
        return 1;
    }

    asm volatile("fence rw, rw" ::: "memory");

    t1 = read_cycle();
    printf("HW cycles: %lu\n", (unsigned long)(t1 - t0));

    dump_row(0);
    dump_row(1);
    dump_row(2);
    dump_row(16);
    dump_row(29);
    dump_row(31);

    return 0;
}

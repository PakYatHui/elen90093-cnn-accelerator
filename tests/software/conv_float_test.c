// =============================================================================
// conv_float_test.c
// ELEN90093 CNN Convolution Accelerator - Float16 Functional Test
//
// Tests 1x1, 3x3, and 5x5 convolution by comparing accelerator output
// against a software reference implementation.
//
// Floating-point format: IEEE-754 half precision stored as uint16_t raw bits.
// All matrices are 32x32 elements of uint16_t.
// =============================================================================

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "rocc.h"

// =============================================================================
// Hardware parameters
// =============================================================================
#define INPUT_SIZE   32
#define INPUT_ELEMS  (INPUT_SIZE * INPUT_SIZE)

// funct7 encodings
#define FUNCT_CONFIG  0
#define FUNCT_DATA    1
#define FUNCT_COMPUTE 2
#define FUNCT_STORE   3
#define FUNCT_KERNEL  4

// RoCC uses custom0 opcode
#define ROCC_X 0

// Data type encoding
#define DATA_FIXED16 0
#define DATA_FLOAT16 1

// CONFIG rs2 layout:
// bits [1:0] = data type
// bit  [2]   = bias enable
#define CONFIG_FLAGS(data_type, bias_enable) \
    (((uint64_t)(data_type) & 0x3ULL) | ((((uint64_t)(bias_enable)) & 0x1ULL) << 2))

// =============================================================================
// Float16 conversion helpers
// =============================================================================

static uint16_t float_to_half_bits(float f) {
    union {
        float f;
        uint32_t u;
    } v;

    v.f = f;

    uint32_t x = v.u;
    uint32_t sign = (x >> 16) & 0x8000;
    uint32_t exp  = (x >> 23) & 0xff;
    uint32_t mant = x & 0x7fffff;

    if (exp == 0xff) {
        if (mant != 0) {
            return (uint16_t)(sign | 0x7e00);  // NaN
        }
        return (uint16_t)(sign | 0x7c00);      // Inf
    }

    int new_exp = (int)exp - 127 + 15;

    if (new_exp >= 31) {
        return (uint16_t)(sign | 0x7c00);      // Overflow -> Inf
    }

    if (new_exp <= 0) {
        if (new_exp < -10) {
            return (uint16_t)sign;             // Underflow -> zero
        }

        mant |= 0x800000;

        int shift = 14 - new_exp;
        uint32_t half_mant = mant >> shift;
        uint32_t rem = mant & ((1u << shift) - 1u);
        uint32_t halfway = 1u << (shift - 1);

        if (rem > halfway || (rem == halfway && (half_mant & 1u))) {
            half_mant++;
        }

        return (uint16_t)(sign | half_mant);
    }

    uint32_t half_exp = (uint32_t)new_exp << 10;
    uint32_t half_mant = mant >> 13;
    uint32_t rem = mant & 0x1fff;
    uint32_t halfway = 0x1000;

    if (rem > halfway || (rem == halfway && (half_mant & 1u))) {
        half_mant++;

        if (half_mant == 0x400) {
            half_mant = 0;
            new_exp++;

            if (new_exp >= 31) {
                return (uint16_t)(sign | 0x7c00);
            }

            half_exp = (uint32_t)new_exp << 10;
        }
    }

    return (uint16_t)(sign | half_exp | half_mant);
}

static float half_bits_to_float(uint16_t h) {
    uint32_t sign = ((uint32_t)h & 0x8000) << 16;
    uint32_t exp  = ((uint32_t)h >> 10) & 0x1f;
    uint32_t mant = (uint32_t)h & 0x03ff;

    uint32_t bits;

    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            int e = -14;

            while ((mant & 0x0400) == 0) {
                mant <<= 1;
                e--;
            }

            mant &= 0x03ff;

            uint32_t exp32 = (uint32_t)(e + 127);
            bits = sign | (exp32 << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000 | (mant << 13);
    } else {
        uint32_t exp32 = exp - 15 + 127;
        bits = sign | (exp32 << 23) | (mant << 13);
    }

    union {
        uint32_t u;
        float f;
    } v;

    v.u = bits;
    return v.f;
}

static uint16_t half_add(uint16_t a, uint16_t b) {
    float af = half_bits_to_float(a);
    float bf = half_bits_to_float(b);
    return float_to_half_bits(af + bf);
}

static uint16_t half_mul(uint16_t a, uint16_t b) {
    float af = half_bits_to_float(a);
    float bf = half_bits_to_float(b);
    return float_to_half_bits(af * bf);
}

// =============================================================================
// RoCC instruction wrappers
// =============================================================================

// CONFIG:
// rs1 = kernel_size
// rs2 = config flags: data type + bias enable
static inline uint64_t conv_config(uint64_t kernel_size, uint64_t config_flags) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         kernel_size, config_flags,
                         FUNCT_CONFIG);
    return ret;
}

// KERNEL:
// rs1 = kernel weight address
// rs2 = bias address
static inline uint64_t conv_kernel(void *kernel_addr, void *bias_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)kernel_addr, (uint64_t)bias_addr,
                         FUNCT_KERNEL);
    return ret;
}

// DATA:
// rs1 = input data address
// rs2 = output data address
static inline uint64_t conv_data(void *input_addr, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)input_addr, (uint64_t)output_addr,
                         FUNCT_DATA);
    return ret;
}

// COMPUTE:
// no operands
static inline uint64_t conv_compute(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_COMPUTE);
    return ret;
}

// STORE:
// no operands
static inline uint64_t conv_store(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_STORE);
    return ret;
}

// =============================================================================
// Software golden reference
// =============================================================================
static void sw_conv_float16(
    const uint16_t *input,
    const uint16_t *kernel,
    uint16_t       *output,
    int             kernel_size)
{
    int radius = kernel_size / 2;

    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            uint16_t sum = float_to_half_bits(0.0f);

            for (int kr = 0; kr < kernel_size; kr++) {
                for (int kc = 0; kc < kernel_size; kc++) {
                    int in_row = row + kr - radius;
                    int in_col = col + kc - radius;

                    uint16_t in_val = float_to_half_bits(0.0f);

                    if (in_row >= 0 && in_row < INPUT_SIZE &&
                        in_col >= 0 && in_col < INPUT_SIZE) {
                        in_val = input[in_row * INPUT_SIZE + in_col];
                    }

                    uint16_t ker_val = kernel[kr * kernel_size + kc];

                    // Simulate half-precision multiply followed by half-precision add.
                    uint16_t product = half_mul(in_val, ker_val);
                    sum = half_add(sum, product);
                }
            }

            output[row * INPUT_SIZE + col] = sum;
        }
    }
}

// =============================================================================
// Test data buffers
// =============================================================================
static uint16_t input_buf [INPUT_ELEMS] __attribute__((aligned(64)));
static uint16_t kernel_buf[5 * 5]       __attribute__((aligned(64)));
static uint16_t hw_output [INPUT_ELEMS] __attribute__((aligned(64)));
static uint16_t sw_output [INPUT_ELEMS] __attribute__((aligned(64)));
// Forward declaration
static int check_output(const char *test_name);

static inline uint64_t read_cycle(void) {
    uint64_t cycle;
    asm volatile ("rdcycle %0" : "=r"(cycle));
    return cycle;
}

// =============================================================================
// Run one complete test
// =============================================================================
static int run_test(const char *name, int kernel_size) {
    memset(hw_output, 0, sizeof(hw_output));
    memset(sw_output, 0, sizeof(sw_output));

    printf("\n=== %s (kernel=%dx%d, float16) ===\n", name, kernel_size, kernel_size);

    // Fill input: value at (row, col) = row + col.
    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            input_buf[row * INPUT_SIZE + col] = float_to_half_bits((float)(row + col));
        }
    }

    // Fill kernel: identity-like kernel.
    memset(kernel_buf, 0, sizeof(kernel_buf));

    if (kernel_size == 1) {
        kernel_buf[0] = float_to_half_bits(1.0f);
    } else {
        int centre = (kernel_size / 2) * kernel_size + (kernel_size / 2);
        kernel_buf[centre] = float_to_half_bits(1.0f);
    }

    uint64_t t0, t1;
    uint64_t ret;

    // Ensure CPU writes to input/kernel/output buffers are visible.
    asm volatile("fence rw, rw" ::: "memory");

    t0 = read_cycle();

    // CONFIG: float16, no bias.
    ret = conv_config((uint64_t)kernel_size,
                      CONFIG_FLAGS(DATA_FLOAT16, 0));
    if (!ret) {
        printf("[%s] CONFIG failed\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    // KERNEL: kernel address + bias address.
    // Bias is disabled, so bias address is zero.
    ret = conv_kernel(kernel_buf, (void *)0);
    if (!ret) {
        printf("[%s] KERNEL failed\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    // DATA: input address + output address.
    ret = conv_data(input_buf, hw_output);
    if (!ret) {
        printf("[%s] DATA failed\n", name);
        return 0;
    }

    ret = conv_compute();
    if (!ret) {
        printf("[%s] COMPUTE failed\n", name);
        return 0;
    }

    ret = conv_store();
    if (!ret) {
        printf("[%s] STORE failed\n", name);
        return 0;
    }

    t1 = read_cycle();

    uint64_t hw_cycles = t1 - t0;

    // Ensure CPU sees accelerator memory writes.
    asm volatile("fence rw, rw" ::: "memory");

    // Software reference.
    t0 = read_cycle();
    sw_conv_float16(input_buf, kernel_buf, sw_output, kernel_size);
    t1 = read_cycle();

    uint64_t sw_cycles = t1 - t0;

    printf("  HW cycles: %lu\n", (unsigned long)hw_cycles);
    printf("  SW cycles: %lu\n", (unsigned long)sw_cycles);

    if (hw_cycles > 0) {
        printf("  Speedup:   %lu x\n", (unsigned long)(sw_cycles / hw_cycles));
    }

    return check_output(name);
}

// =============================================================================
// Compare hardware and software output
// =============================================================================
static int check_output(const char *test_name) {
    printf("[%s] First 8 outputs:\n", test_name);
    printf("  idx | sw_output        | hw_output\n");

    for (int i = 0; i < 9; i++) {
        printf("  [%d] | 0x%04x (%.4f) | 0x%04x (%.4f)\n",
               i,
               sw_output[i], half_bits_to_float(sw_output[i]),
               hw_output[i], half_bits_to_float(hw_output[i]));
    }

    int pass = 1;

    for (int i = 0; i < INPUT_ELEMS; i++) {
        if (hw_output[i] != sw_output[i]) {
            printf("[%s] MISMATCH at [%d][%d]: hw=0x%04x (%.4f) sw=0x%04x (%.4f)\n",
                   test_name,
                   i / INPUT_SIZE,
                   i % INPUT_SIZE,
                   hw_output[i], half_bits_to_float(hw_output[i]),
                   sw_output[i], half_bits_to_float(sw_output[i]));

            pass = 0;
            break;
        }
    }

    if (pass) {
        printf("[%s] PASS - all %d outputs match\n", test_name, INPUT_ELEMS);
    }

    return pass;
}

// =============================================================================
// Main
// =============================================================================
int main(void) {
    printf("=== ELEN90093 Convolution Accelerator Float16 Test ===\n");

    int all_pass = 1;

    all_pass &= run_test("Float16_Test1_1x1", 1);
    all_pass &= run_test("Float16_Test2_3x3", 3);
    all_pass &= run_test("Float16_Test3_5x5", 5);

    printf("\n=== Final Result: %s ===\n", all_pass ? "ALL PASS" : "SOME FAILED");

    return 0;
}

#ifndef VISITOR_H_
#define VISITOR_H_

#include <stdexcept>

#include "ASTNode.h"

class Visitor {
public:
    virtual void operator()(const std::shared_ptr<ASTNode> op) {
        switch (op->nodeType()) {

#define DISPATCH_CASE(name) \
            case ASTNodeType::name: \
                visit(static_cast<name##Node*>(op.get())); \
                break;

            DISPATCH_CASE(Program)
            DISPATCH_CASE(Function)
            DISPATCH_CASE(StmtSeq)
            DISPATCH_CASE(Integer)
            DISPATCH_CASE(Var)
            DISPATCH_CASE(Assign)
            DISPATCH_CASE(Invoke)
            DISPATCH_CASE(IfThenElse)
            DISPATCH_CASE(While)
            DISPATCH_CASE(Call)
            DISPATCH_CASE(Add)
            DISPATCH_CASE(Sub)
            DISPATCH_CASE(Mul)
            DISPATCH_CASE(Div)
            DISPATCH_CASE(LT)
            DISPATCH_CASE(LE)
            DISPATCH_CASE(GT)
            DISPATCH_CASE(GE)
            DISPATCH_CASE(EQ)
            DISPATCH_CASE(NE)

            default:
                throw std::runtime_error("Unrecognized ASTNodeType");
        }
    }

protected:
    virtual void visit(const ProgramNode *op) {
        for (auto &&func : op->funcs_) {
            (*this)(func);
        }
    }

    virtual void visit(const FunctionNode *op) {
        (*this)(op->body_);
    }

    virtual void visit(const StmtSeqNode *op) {
        for (auto &&stmt : op->stmts_) {
            (*this)(stmt);
        }
    }

    virtual void visit(const IntegerNode *op) {}

    virtual void visit(const VarNode *op) {}

    virtual void visit(const AssignNode *op) {
        (*this)(op->var_);
        (*this)(op->expr_);
    }

    virtual void visit(const IfThenElseNode *op) {
        (*this)(op->cond_);
        (*this)(op->thenCase_);
        if (op->elseCase_ != nullptr) {
            (*this)(op->elseCase_);
        }
    }

    virtual void visit(const WhileNode *op) {
        (*this)(op->cond_);
        (*this)(op->body_);
    }

    virtual void visit(const InvokeNode *op) {
        (*this)(op->expr_);
    }

    virtual void visit(const CallNode *op) {
        // nothing
    }

#define VISIT_BINARY_NODE(name) \
    virtual void visit(const name##Node *op) { \
        (*this)(op->lhs_); \
        (*this)(op->rhs_); \
    }
    VISIT_BINARY_NODE(Add)
    VISIT_BINARY_NODE(Sub)
    VISIT_BINARY_NODE(Mul)
    VISIT_BINARY_NODE(Div)
    VISIT_BINARY_NODE(LT)
    VISIT_BINARY_NODE(LE)
    VISIT_BINARY_NODE(GT)
    VISIT_BINARY_NODE(GE)
    VISIT_BINARY_NODE(EQ)
    VISIT_BINARY_NODE(NE)
};

#endif  // VISITOR_H_

{#
# Python has a built-in `enum` module which is nice to use, but doesn't support
# variants with associated data. So, we switch here, and generate a stdlib `enum`
# when none of the variants have associated data, or a generic nested-class
# construct when they do.
#}
{%- let e = self.inner() %}
{% if e.is_flat() %}

class {{ e|type_name }}(ViaFfiUsingByteBuffer, enum.Enum):
    {% for variant in e.variants() -%}
    {{ variant.name()|enum_variant_py }} = {{ loop.index }}
    {% endfor %}

    @staticmethod
    def _read(buf):
        variant = buf.readI32()
        {% for variant in e.variants() -%}
        if variant == {{ loop.index }}:
            return {{ e|type_name }}.{{ variant.name()|enum_variant_py }}
        {% endfor %}
        raise InternalError("Raw enum value doesn't match any cases")

    def _write(self, buf):
        {% for variant in e.variants() -%}
        if self is {{ e|type_name }}.{{ variant.name()|enum_variant_py }}:
            i = {{loop.index}}
            buf.writeI32({{ loop.index }})
        {% endfor %}
{% else %}

class {{ e|type_name }}(ViaFfiUsingByteBuffer, object):
    def __init__(self):
        raise RuntimeError("{{ e|type_name }} cannot be instantiated directly")

    # Each enum variant is a nested class of the enum itself.
    {% for variant in e.variants() -%}
    class {{ variant.name()|enum_variant_py }}(object):
        def __init__(self,{% for field in variant.fields() %}{{ field.name()|var_name }}{% if loop.last %}{% else %}, {% endif %}{% endfor %}):
            {% if variant.has_fields() %}
            {%- for field in variant.fields() %}
            self.{{ field.name()|var_name }} = {{ field.name()|var_name }}
            {%- endfor %}
            {% else %}
            pass
            {% endif %}

        def __str__(self):
            return "{{ e|type_name }}.{{ variant.name()|enum_variant_py }}({% for field in variant.fields() %}{{ field.name() }}={}{% if loop.last %}{% else %}, {% endif %}{% endfor %})".format({% for field in variant.fields() %}self.{{ field.name() }}{% if loop.last %}{% else %}, {% endif %}{% endfor %})

        def __eq__(self, other):
            if not other.is_{{ variant.name()|var_name }}():
                return False
            {%- for field in variant.fields() %}
            if self.{{ field.name()|var_name }} != other.{{ field.name()|var_name }}:
                return False
            {%- endfor %}
            return True
    {% endfor %}

    # For each variant, we have an `is_NAME` method for easily checking
    # whether an instance is that variant.
    {% for variant in e.variants() -%}
    def is_{{ variant.name()|var_name }}(self):
        return isinstance(self, {{ e|type_name }}.{{ variant.name()|enum_variant_py }})
    {% endfor %}

    @classmethod
    def _read(cls, buf):
        variant = buf.readI32()

        {% for variant in e.variants() -%}
        if variant == {{ loop.index }}:
            return cls.{{variant.name()|enum_variant_py}}(
                {%- for field in variant.fields() %}
                {{ field.name()|var_name }}={{ "buf"|read_var(field.type_()) }},
                {%- endfor %}
            )
        {% endfor %}
        raise InternalError("Raw enum value doesn't match any cases")

    def _write(self, buf):
        {% for variant in e.variants() -%}
        if self.is_{{ variant.name()|var_name }}():
            buf.writeI32({{ loop.index }})
            {%- for field in variant.fields() %}
            {{ "self.{}"|format(field.name())|write_var("buf", field.type_()) }}
            {%- endfor %}
        {% endfor %}

# Now, a little trick - we make each nested variant class be a subclass of the main
# enum class, so that method calls and instance checks etc will work intuitively.
# We might be able to do this a little more neatly with a metaclass, but this'll do.
{% for variant in e.variants() -%}
{{ e|type_name }}.{{ variant.name()|enum_variant_py }} = type("{{ e|type_name }}.{{ variant.name()|enum_variant_py }}", ({{ e|type_name }}.{{variant.name()|enum_variant_py}}, {{ e|type_name }},), {})
{% endfor %}

{% endif %}
